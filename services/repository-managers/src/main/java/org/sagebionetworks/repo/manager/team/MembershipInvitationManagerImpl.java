/**
 * 
 */
package org.sagebionetworks.repo.manager.team;

import static org.sagebionetworks.repo.manager.EmailUtils.TEMPLATE_KEY_INVITER_MESSAGE;
import static org.sagebionetworks.repo.manager.EmailUtils.TEMPLATE_KEY_ONE_CLICK_JOIN;
import static org.sagebionetworks.repo.manager.EmailUtils.TEMPLATE_KEY_TEAM_NAME;
import static org.sagebionetworks.repo.manager.EmailUtils.TEMPLATE_KEY_TEAM_ID;

import java.util.*;

import com.amazonaws.services.simpleemail.model.SendRawEmailRequest;
import org.apache.http.entity.ContentType;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.manager.*;
import org.sagebionetworks.repo.manager.principal.SynapseEmailService;
import org.sagebionetworks.repo.model.*;
import org.sagebionetworks.repo.model.dbo.principal.DBOPrincipalAlias;
import org.sagebionetworks.repo.model.message.MessageToUser;
import org.sagebionetworks.repo.model.principal.AliasType;
import org.sagebionetworks.repo.model.principal.PrincipalAlias;
import org.sagebionetworks.repo.model.principal.PrincipalAliasDAO;
import org.sagebionetworks.repo.transactions.WriteTransactionReadCommitted;
import org.sagebionetworks.repo.util.SignedTokenUtil;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author brucehoff
 *
 */
public class MembershipInvitationManagerImpl implements
		MembershipInvitationManager {

	@Autowired
	private AuthorizationManager authorizationManager;
	@Autowired 
	private MembershipInvtnSubmissionDAO membershipInvtnSubmissionDAO;
	@Autowired
	private TeamDAO teamDAO;
	@Autowired
	private SynapseEmailService sesClient;
	@Autowired
	private PrincipalAliasDAO principalAliasDAO;

	public static final String TEAM_MEMBERSHIP_INVITATION_EXTENDED_TEMPLATE = "message/teamMembershipInvitationExtendedTemplate.html";

	private static final String TEAM_MEMBERSHIP_INVITATION_MESSAGE_SUBJECT = "You Have Been Invited to Join a Team";

	protected static final long SIGNED_TOKEN_EXPIRATION_TIME = 1000 * 60 * 60 * 24;

	public static void validateForCreate(MembershipInvtnSubmission mis) {
		if (mis.getCreatedBy()!=null) throw new InvalidModelException("'createdBy' field is not user specifiable.");
		if (mis.getCreatedOn()!=null) throw new InvalidModelException("'createdOn' field is not user specifiable.");
		if (mis.getId()!=null) throw new InvalidModelException("'id' field is not user specifiable.");
		if ((mis.getInviteeId() == null ^ mis.getInviteeEmail() == null) == false) {
			throw new InvalidModelException("One and only one of the fields 'inviteeId' and 'inviteeEmail' is required");
		}
		if (mis.getTeamId()==null) throw new InvalidModelException("'teamId' field is required.");
	}

	public static void populateCreationFields(UserInfo userInfo, MembershipInvtnSubmission mis, Date now) {
		mis.setCreatedBy(userInfo.getId().toString());
		mis.setCreatedOn(now);
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.manager.team.MembershipInvitationManager#create(org.sagebionetworks.repo.model.UserInfo, org.sagebionetworks.repo.model.MembershipInvtnSubmission)
	 */
	@WriteTransactionReadCommitted
	@Override
	public MembershipInvtnSubmission create(UserInfo userInfo,
			MembershipInvtnSubmission mis) throws DatastoreException,
			InvalidModelException, UnauthorizedException, NotFoundException {
		validateForCreate(mis);
		if (!authorizationManager.canAccessMembershipInvitationSubmission(userInfo, mis, ACCESS_TYPE.CREATE).getAuthorized())
			throw new UnauthorizedException("Cannot create membership invitation.");
		Date now = new Date();
		populateCreationFields(userInfo, mis, now);
		MembershipInvtnSubmission created = membershipInvtnSubmissionDAO.create(mis);
		return created;
	}
	
	@Override
	public MessageToUserAndBody createInvitationToUser(MembershipInvtnSubmission mis,
			String acceptInvitationEndpoint, String notificationUnsubscribeEndpoint) {
		if (acceptInvitationEndpoint==null || notificationUnsubscribeEndpoint==null) return null;
		if (mis.getCreatedOn() == null) mis.setCreatedOn(new Date());
		MessageToUser mtu = new MessageToUser();
		mtu.setSubject(TEAM_MEMBERSHIP_INVITATION_MESSAGE_SUBJECT);
		mtu.setRecipients(Collections.singleton(mis.getInviteeId()));
		mtu.setNotificationUnsubscribeEndpoint(notificationUnsubscribeEndpoint);
		Map<String,String> fieldValues = new HashMap<String,String>();
		fieldValues.put(TEMPLATE_KEY_TEAM_NAME, teamDAO.get(mis.getTeamId()).getName());
		fieldValues.put(TEMPLATE_KEY_TEAM_ID, mis.getTeamId());
		fieldValues.put(TEMPLATE_KEY_ONE_CLICK_JOIN, EmailUtils.createOneClickJoinTeamLink(
				acceptInvitationEndpoint, mis.getInviteeId(), mis.getInviteeId(), mis.getTeamId(), mis.getCreatedOn()));
		if (mis.getMessage()==null || mis.getMessage().length()==0) {
			fieldValues.put(TEMPLATE_KEY_INVITER_MESSAGE, "");
		} else {
			fieldValues.put(TEMPLATE_KEY_INVITER_MESSAGE, 
							"The inviter sends the following message: <Blockquote> "+
							mis.getMessage()+" </Blockquote> ");
		}
		String messageContent = EmailUtils.readMailTemplate(TEAM_MEMBERSHIP_INVITATION_EXTENDED_TEMPLATE, fieldValues);
		return new MessageToUserAndBody(mtu, messageContent, ContentType.TEXT_HTML.getMimeType());
	}

	@Override
	public void sendInvitationToEmail(MembershipInvtnSubmission mis,
			String acceptInvitationEndpoint, String notificationUnsubscribeEndpoint) {
		if (acceptInvitationEndpoint==null || notificationUnsubscribeEndpoint==null) return;
		String teamName = teamDAO.get(mis.getTeamId()).getName();
		String subject = "You have been invited to join the team " + teamName;
		String expiresOn = Long.toString(mis.getCreatedOn().getTime() + SIGNED_TOKEN_EXPIRATION_TIME);
		Map<String,String> fieldValues = new HashMap<>();
		fieldValues.put(EmailUtils.TEMPLATE_KEY_TEAM_ID, mis.getTeamId());
		fieldValues.put(EmailUtils.TEMPLATE_KEY_TEAM_NAME, teamName);
		fieldValues.put(EmailUtils.TEMPLATE_KEY_ONE_CLICK_JOIN, EmailUtils.createMembershipInvtnLink(acceptInvitationEndpoint, mis.getId(), expiresOn));
		fieldValues.put(EmailUtils.TEMPLATE_KEY_INVITER_MESSAGE, mis.getMessage());
		String messageBody = EmailUtils.readMailTemplate("message/teamMembershipInvitationExtendedToEmailTemplate.html", fieldValues);
		SendRawEmailRequest sendEmailRequest = new SendRawEmailRequestBuilder()
			.withRecipientEmail(mis.getInviteeEmail())
			.withSubject(subject)
			.withBody(messageBody, SendRawEmailRequestBuilder.BodyType.HTML)
			.withIsNotificationMessage(true)
			.build();
		sesClient.sendRawEmail(sendEmailRequest);
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.manager.team.MembershipInvitationManager#get(org.sagebionetworks.repo.model.UserInfo, java.lang.String)
	 */
	@Override
	public MembershipInvtnSubmission get(UserInfo userInfo, String id)
			throws DatastoreException, NotFoundException {
		MembershipInvtnSubmission mis = membershipInvtnSubmissionDAO.get(id);
		if (!authorizationManager.canAccessMembershipInvitationSubmission(userInfo, mis, ACCESS_TYPE.READ).getAuthorized())
			throw new UnauthorizedException("Cannot retrieve membership invitation.");
		return mis;
	}

	@Override
	public MembershipInvtnSubmission get(String misId, MembershipInvtnSignedToken token) throws DatastoreException, NotFoundException {
		AuthorizationStatus status = authorizationManager.canAccessMembershipInvitationSubmission(token, ACCESS_TYPE.READ);
		if (!status.getAuthorized()) {
			throw new UnauthorizedException(status.getReason());
		}
		return membershipInvtnSubmissionDAO.get(misId);
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.manager.team.MembershipInvitationManager#delete(org.sagebionetworks.repo.model.UserInfo, java.lang.String)
	 */
	@WriteTransactionReadCommitted
	@Override
	public void delete(UserInfo userInfo, String id) throws DatastoreException,
			UnauthorizedException, NotFoundException {
		MembershipInvtnSubmission mis = null;
		try {
			mis = membershipInvtnSubmissionDAO.get(id);
		} catch (NotFoundException e) {
			return;
		}
		if (!authorizationManager.canAccessMembershipInvitationSubmission(userInfo, mis, ACCESS_TYPE.DELETE).getAuthorized())
			throw new UnauthorizedException("Cannot delete membership invitation.");
		membershipInvtnSubmissionDAO.delete(id);
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.manager.team.MembershipInvitationManager#getOpenForUserInRange(java.lang.String, long, long)
	 */
	@Override
	public PaginatedResults<MembershipInvitation> getOpenForUserInRange(
			String principalId, long limit, long offset)
			throws DatastoreException, NotFoundException {
		Date now = new Date();
		long principalIdAsLong = Long.parseLong(principalId);
		List<MembershipInvitation> miList = membershipInvtnSubmissionDAO.getOpenByUserInRange(principalIdAsLong, now.getTime(), limit, offset);
		long count = membershipInvtnSubmissionDAO.getOpenByUserCount(principalIdAsLong, now.getTime());
		PaginatedResults<MembershipInvitation> results = new PaginatedResults<MembershipInvitation>();
		results.setResults(miList);
		results.setTotalNumberOfResults(count);
		return results;
	}

	@Override
	public Count getOpenInvitationCountForUser(String principalId) {
		ValidateArgument.required(principalId, "principalId");
		Count result = new Count();
		long count = membershipInvtnSubmissionDAO.getOpenByUserCount(Long.parseLong(principalId), System.currentTimeMillis());
		result.setCount(count);
		return result;
	}

	@Override
	public InviteeVerificationSignedToken verifyInvitee(Long userId, String membershipInvitationId, MembershipInvtnSignedToken token) {
		if (!membershipInvitationId.equals(token.getMembershipInvitationId())) {
			throw new IllegalArgumentException("ID in URI and ID in signed token don't match");
		}
		ValidateArgument.required(userId, "userId");
		ValidateArgument.required(membershipInvitationId, "membershipInvitationId");
		SignedTokenUtil.validateToken(token);
		if (Long.parseLong(token.getExpiresOn()) < new Date().getTime()) {
			throw new IllegalArgumentException("MembershipInvtnSignedToken is expired");
		}
		// Get inviteeEmail from membershipInvitation
		String inviteeEmail = membershipInvtnSubmissionDAO.getInviteeEmail(membershipInvitationId);
		// Find out if the given user has an alias that matches the inviteeEmail
		PrincipalAlias principalAlias = principalAliasDAO.findPrincipalWithAlias(inviteeEmail);
		if (principalAlias == null || !principalAlias.getPrincipalId().equals(userId) || !principalAlias.getAlias().equals(inviteeEmail)) {
			// inviteeEmail is not associated with the user
			throw new UnauthorizedException("This membership invitation is not addressed to the authenticated user.");
		}
		InviteeVerificationSignedToken response = new InviteeVerificationSignedToken();
		response.setInviteeId(userId.toString());
		response.setMembershipInvitationId(membershipInvitationId);
		SignedTokenUtil.signToken(response);
		return response;
	}

	@WriteTransactionReadCommitted
	@Override
	public void updateInviteeId(Long userId, String misId, InviteeVerificationSignedToken token) {
		if (!misId.equals(token.getMembershipInvitationId())) {
			throw new IllegalArgumentException("ID in URI and ID in signed token don't match");
		}
		AuthorizationStatus status = authorizationManager.canAccessMembershipInvitationSubmission(userId, token, ACCESS_TYPE.UPDATE);
		if (!status.getAuthorized()) {
			throw new UnauthorizedException(status.getReason());
		}
		MembershipInvtnSubmission mis = membershipInvtnSubmissionDAO.get(misId);
		if (!(mis.getInviteeId() == null && mis.getInviteeEmail() != null)) {
			throw new UnauthorizedException("Cannot update inviteeId. The target invitation must have a null inviteeId and a non null inviteeEmail.");
		}
		int rowsUpdated = membershipInvtnSubmissionDAO.updateInviteeId(misId, userId);
		if (rowsUpdated != 1) {
			throw new DatastoreException("Expected to update 1 row, but instead updated " + rowsUpdated + " rows.");
		}
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.manager.team.MembershipInvitationManager#getOpenForUserAndTeamInRange(java.lang.String, java.lang.String, long, long)
	 */
	@Override
	public PaginatedResults<MembershipInvitation> getOpenForUserAndTeamInRange(
			String principalId, String teamId, long limit, long offset)
			throws DatastoreException, NotFoundException {
		Date now = new Date();
		long principalIdAsLong = Long.parseLong(principalId);
		long teamIdAsLong = Long.parseLong(teamId);
		List<MembershipInvitation> miList = membershipInvtnSubmissionDAO.getOpenByTeamAndUserInRange(teamIdAsLong, principalIdAsLong, now.getTime(), limit, offset);
		long count = membershipInvtnSubmissionDAO.getOpenByTeamAndUserCount(teamIdAsLong, principalIdAsLong, now.getTime());
		PaginatedResults<MembershipInvitation> results = new PaginatedResults<MembershipInvitation>();
		results.setResults(miList);
		results.setTotalNumberOfResults(count);
		return results;
	}

	@Override
	public PaginatedResults<MembershipInvtnSubmission> getOpenSubmissionsForTeamInRange(
			UserInfo userInfo, String teamId, long limit, long offset) throws NotFoundException {
		if (!authorizationManager.canAccess(
				userInfo, teamId, ObjectType.TEAM, ACCESS_TYPE.TEAM_MEMBERSHIP_UPDATE).getAuthorized()) 
			throw new UnauthorizedException("Cannot retrieve membership invitations for team "+teamId+".");
		Date now = new Date();
		long teamIdAsLong = Long.parseLong(teamId);
		List<MembershipInvtnSubmission> miList = membershipInvtnSubmissionDAO.getOpenSubmissionsByTeamInRange(teamIdAsLong, now.getTime(), limit, offset);
		long count = membershipInvtnSubmissionDAO.getOpenByTeamCount(teamIdAsLong, now.getTime());
		PaginatedResults<MembershipInvtnSubmission> results = new PaginatedResults<MembershipInvtnSubmission>();
		results.setResults(miList);
		results.setTotalNumberOfResults(count);
		return results;
	}

	@Override
	public PaginatedResults<MembershipInvtnSubmission> getOpenSubmissionsForUserAndTeamInRange(
			UserInfo userInfo, String inviteeId, String teamId, long limit,
			long offset) throws DatastoreException, NotFoundException {
		if (!authorizationManager.canAccess(
				userInfo, teamId, ObjectType.TEAM, ACCESS_TYPE.TEAM_MEMBERSHIP_UPDATE).getAuthorized()) 
			throw new UnauthorizedException("Cannot retrieve membership invitations for team "+teamId+".");
		Date now = new Date();
		long teamIdAsLong = Long.parseLong(teamId);
		long inviteeIdAsLong = Long.parseLong(inviteeId);
		List<MembershipInvtnSubmission> miList = membershipInvtnSubmissionDAO.getOpenSubmissionsByTeamAndUserInRange(teamIdAsLong, inviteeIdAsLong, now.getTime(), limit, offset);
		long count = membershipInvtnSubmissionDAO.getOpenByTeamCount(teamIdAsLong, now.getTime());
		PaginatedResults<MembershipInvtnSubmission> results = new PaginatedResults<MembershipInvtnSubmission>();
		results.setResults(miList);
		results.setTotalNumberOfResults(count);
		return results;
	}

}
