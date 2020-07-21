package org.sagebionetworks.repo.manager.dataaccess;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.MessageManager;
import org.sagebionetworks.repo.manager.UserManager;
import org.sagebionetworks.repo.manager.dataaccess.notifications.DataAccessNotificationBuilder;
import org.sagebionetworks.repo.manager.feature.FeatureManager;
import org.sagebionetworks.repo.manager.file.FileHandleManager;
import org.sagebionetworks.repo.manager.stack.ProdDetector;
import org.sagebionetworks.repo.model.AccessApproval;
import org.sagebionetworks.repo.model.AccessApprovalDAO;
import org.sagebionetworks.repo.model.AccessRequirement;
import org.sagebionetworks.repo.model.AccessRequirementDAO;
import org.sagebionetworks.repo.model.ApprovalState;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.ManagedACTAccessRequirement;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.dataaccess.DataAccessNotificationType;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DBODataAccessNotification;
import org.sagebionetworks.repo.model.dbo.dao.dataaccess.DataAccessNotificationDao;
import org.sagebionetworks.repo.model.dbo.feature.Feature;
import org.sagebionetworks.repo.model.message.ChangeMessage;
import org.sagebionetworks.repo.model.message.ChangeType;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.util.ValidateArgument;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AccessApprovalNotificationManagerImpl implements AccessApprovalNotificationManager {

	private static final Logger LOG = LogManager.getLogger(AccessApprovalNotificationManagerImpl.class);
	
	private static final String MSG_NOT_DELIVERED = "{} notification (AR: {}, Recipient: {}, AP: {}) will not be delivered.";

	// Do not re-send another notification if one was sent already within the last 7
	// days
	private static final long REVOKE_RESEND_TIMEOUT_DAYS = 7;

	// Do not process a change message if it's older than 24 hours
	private static final long CHANGE_TIMEOUT_HOURS = 24;

	// Fake id for messages to that are not actually created, e.g. in staging we do not send the messages
	// to avoid having duplicate messages sent out
	private static final long NO_MESSAGE_TO_USER = -1;

	private UserManager userManager;
	private DataAccessNotificationDao notificationDao;
	private AccessApprovalDAO accessApprovalDao;
	private AccessRequirementDAO accessRequirementDao;
	private FileHandleManager fileHandleManager;
	private MessageManager messageManager;
	private FeatureManager featureManager;
	private ProdDetector prodDetector;

	/**
	 * The map is initialized by
	 * {@link #configureDataAccessNotificationBuilders(List)} on bean creation
	 */
	private Map<DataAccessNotificationType, DataAccessNotificationBuilder> notificationBuilders;

	@Autowired
	public AccessApprovalNotificationManagerImpl(final UserManager userManager,
			final DataAccessNotificationDao notificationDao, final AccessApprovalDAO accessApprovalDao,
			final AccessRequirementDAO accessRequirementDao, final FileHandleManager fileHandleManager,
			final MessageManager messageManager, final FeatureManager featureTesting, final ProdDetector prodDetector) {
		this.userManager = userManager;
		this.notificationDao = notificationDao;
		this.accessApprovalDao = accessApprovalDao;
		this.accessRequirementDao = accessRequirementDao;
		this.fileHandleManager = fileHandleManager;
		this.messageManager = messageManager;
		this.featureManager = featureTesting;
		this.prodDetector = prodDetector;
	}

	@Autowired(required = false)
	public void configureDataAccessNotificationBuilders(List<DataAccessNotificationBuilder> builders) {
		notificationBuilders = new HashMap<>(DataAccessNotificationType.values().length);

		for (DataAccessNotificationBuilder builder : builders) {
			for (DataAccessNotificationType supportedType : builder.supportedTypes()) {
				if (notificationBuilders.containsKey(supportedType)) {
					throw new IllegalStateException(
							"A notification builder for type " + supportedType + " is already registred.");
				}
				notificationBuilders.put(supportedType, builder);
			}
		}

	}

	@Override
	@WriteTransaction
	public void processAccessApprovalChange(ChangeMessage message) throws RecoverableMessageException {
		ValidateArgument.required(message, "The change message");

		// Check if the feature is enabled
		if (!featureManager.isFeatureEnabled(Feature.DATA_ACCESS_RENEWALS)) {
			return;
		}

		// Should we process this change?
		if (discardChangeMessage(message)) {
			return;
		}

		AccessApproval approval = accessApprovalDao.get(message.getObjectId());

		// Should we process this approval change?
		if (discardAccessApproval(approval, ApprovalState.REVOKED)) {
			return;
		}

		UserInfo recipient = getRecipientForRevocation(approval);

		// We need to check if an APPROVED access approval exists already for the same
		// access requirement, in such a case
		// there is no need to send a notification as the user is still considered
		// APPROVED
		if (!accessApprovalDao
				.listApprovalsByAccessor(approval.getRequirementId().toString(), recipient.getId().toString())
				.isEmpty()) {
			return;
		}

		sendMessageIfNeeded(DataAccessNotificationType.REVOCATION, approval, recipient, this::isSendRevocation);

	}

	boolean isSendRevocation(DBODataAccessNotification existingNotification, AccessApproval approval) {
		Instant sentOn = existingNotification.getSentOn().toInstant();
		Instant approvalModifiedOn = approval.getModifiedOn().toInstant();

		// If it was sent after the approval modification then it was already processed
		if (sentOn.isAfter(approvalModifiedOn)) {
			return false;
		}

		// The approval was modified after the notification was sent (e.g. the user was
		// added back and revoked again)
		// We do not want to re-send another notification if the last one for the same
		// approval was within the last week
		if (sentOn.isAfter(approvalModifiedOn.minus(REVOKE_RESEND_TIMEOUT_DAYS, ChronoUnit.DAYS))) {
			return false;
		}

		return true;
	}

	boolean discardChangeMessage(ChangeMessage change) {

		// Check if it's an ACCESS_APPROVAL message
		if (!ObjectType.ACCESS_APPROVAL.equals(change.getObjectType())) {
			return true;
		}

		// Process only UPDATES
		if (!ChangeType.UPDATE.equals(change.getChangeType())) {
			return true;
		}

		// Discard old changes
		if (change.getTimestamp().toInstant().isBefore(Instant.now().minus(CHANGE_TIMEOUT_HOURS, ChronoUnit.HOURS))) {
			return true;
		}

		return false;
	}

	boolean discardAccessApproval(AccessApproval approval, ApprovalState expectedState) {
		// Do not process approvals that are not in the given state
		if (!expectedState.equals(approval.getState())) {
			return true;
		}

		final Long requirementId = approval.getRequirementId();

		String accessRequirementType = accessRequirementDao.getConcreteType(requirementId.toString());

		// Does not process notifications for non-managed access requirements
		if (!ManagedACTAccessRequirement.class.getName().equals(accessRequirementType)) {
			return true;
		}

		return false;
	}

	void sendMessageIfNeeded(DataAccessNotificationType notificationType, AccessApproval approval, UserInfo recipient,
			ReSendCondition reSendCondition) throws RecoverableMessageException {

		final Long requirementId = approval.getRequirementId();
		final Long recipientId = recipient.getId();
		final Long approvalId = approval.getId();

		// We check if a notification was sent out already for the given requirement and recipient, we acquire a lock on the row if
		// it exists
		Optional<DBODataAccessNotification> notification = notificationDao.findForUpdate(notificationType, requirementId, recipientId);

		// If a notification is present we check if we should send another one
		if (notification.isPresent() && !reSendCondition.canSend(notification.get(), approval)) {
			return;
		}

		Long messageId = NO_MESSAGE_TO_USER;
		Instant sentOn = Instant.now();

		// Check if the message can be delivered to the given recipient (e.g. on staging we usually do not want to send notifications)
		if (deliverMessage(recipient)) {
			MessageToUser messageToUser = createMessageToUser(notificationType, approval, recipient);
			messageId = Long.valueOf(messageToUser.getId());
			sentOn = messageToUser.getCreatedOn().toInstant();
		} else {
			LOG.warn(MSG_NOT_DELIVERED, notificationType, requirementId, recipientId, approvalId);
		}

		DBODataAccessNotification toStore = notification.orElse(new DBODataAccessNotification());

		// Align the data for creation/update
		toStore.setNotificationType(notificationType.name());
		toStore.setRequirementId(requirementId);
		toStore.setRecipientId(recipientId);
		toStore.setAccessApprovalId(approvalId);
		toStore.setMessageId(messageId);
		toStore.setSentOn(Timestamp.from(sentOn));

		// If two messages come at the same time, one or the other will fail on creation due to the unique constraint
		// One transaction will roll back and the message to user won't be sent
		if (toStore.getId() == null) {
			notificationDao.create(toStore);
		} else {
			notificationDao.update(toStore.getId(), toStore);
		}
	}

	MessageToUser createMessageToUser(DataAccessNotificationType notificationType, AccessApproval approval,
			UserInfo recipient) {

		DataAccessNotificationBuilder notificationBuidler = getNotificationBuilder(notificationType);

		AccessRequirement accessRequirement = accessRequirementDao.get(approval.getRequirementId().toString());

		if (!(accessRequirement instanceof ManagedACTAccessRequirement)) {
			throw new IllegalStateException("Cannot sent a notification for a non managed access requirement");
		}

		ManagedACTAccessRequirement managedAccessRequriement = (ManagedACTAccessRequirement) accessRequirement;

		UserInfo notificationsSender = getNotificationsSender();

		String sender = notificationsSender.getId().toString();
		String messageBody = notificationBuidler.buildMessageBody(managedAccessRequriement, approval, recipient);
		String mimeType = notificationBuidler.getMimeType();
		String subject = notificationBuidler.buildSubject(managedAccessRequriement, approval, recipient);
		
		// The message to user requires a file handle where the body is stored
		String fileHandleId = storeMessageBody(sender, messageBody, mimeType);

		MessageToUser message = new MessageToUser();

		message.setSubject(subject);
		message.setCreatedBy(sender);
		message.setIsNotificationMessage(false);
		message.setWithUnsubscribeLink(false);
		message.setWithProfileSettingLink(false);
		message.setFileHandleId(fileHandleId);
		message.setRecipients(Collections.singleton(recipient.getId().toString()));

		// We override the user notification setting as we want to send this kind of notifications anyway
		boolean overrideNotificationSettings = true;

		return messageManager.createMessage(notificationsSender, message, overrideNotificationSettings);
	}

	UserInfo getNotificationsSender() {
		return userManager.getUserInfo(BOOTSTRAP_PRINCIPAL.DATA_ACCESS_NOTFICATIONS_SENDER.getPrincipalId());
	}

	boolean deliverMessage(UserInfo recipient) throws RecoverableMessageException {

		// We always deliver the message if the user is in the synapse testing group
		if (featureManager.isUserInTestingGroup(recipient)) {
			return true;
		}

		// We deliver the actual email only in production to avoid duplicate messages
		// sent out both from prod and/or staging
		return prodDetector.isProductionStack().orElseThrow(() -> 
			new RecoverableMessageException("Cannot detect current stack")
		);
	}

	String storeMessageBody(String sender, String messageBody, String mimeType) {
		try {
			return fileHandleManager
					.createCompressedFileFromString(sender, Date.from(Instant.now()), messageBody, mimeType).getId();
		} catch (IOException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	UserInfo getRecipientForRevocation(AccessApproval approval) {
		return userManager.getUserInfo(Long.valueOf(approval.getAccessorId()));
	}

	DataAccessNotificationBuilder getNotificationBuilder(DataAccessNotificationType notificationType) {
		DataAccessNotificationBuilder messageBuilder = notificationBuilders.get(notificationType);

		if (messageBuilder == null) {
			throw new IllegalStateException(
					"Could not find a message builder for " + notificationType + " notification type.");
		}
		return messageBuilder;
	}

	/**
	 * Internal functional interface to check if an existing notification should be
	 * resent for the given access approval
	 * 
	 * @author Marco Marasca
	 */
	@FunctionalInterface
	public static interface ReSendCondition {

		/**
		 * @param existingNotification An existing notification that matches the approval
		 *                             requirement and recipient
		 * @param approval             The approval that matches the given notification
		 * @return True iff a new notification should be sent out at this time for the
		 *         given approval, false otherwise
		 */
		boolean canSend(DBODataAccessNotification existingNotification, AccessApproval approval);

	}

}
