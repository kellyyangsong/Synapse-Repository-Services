package org.sagebionetworks.repo.manager.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.sagebionetworks.repo.manager.entity.EntityAuthorizationManagerImpl;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.ar.AccessRestrictionStatusDao;
import org.sagebionetworks.repo.model.ar.UsersRestrictionStatus;
import org.sagebionetworks.repo.model.auth.UserEntityPermissions;
import org.sagebionetworks.repo.model.dbo.entity.UserEntityPermissionsState;
import org.sagebionetworks.repo.model.dbo.entity.UsersEntityPermissionsDao;
import org.sagebionetworks.repo.model.jdo.KeyFactory;

@ExtendWith(MockitoExtension.class)

public class EntityAuthorizationManagerUnitTest {

	@Mock
	private AccessRestrictionStatusDao mockAccessRestrictionStatusDao;
	@Mock
	private UsersEntityPermissionsDao mockUsersEntityPermissionsDao;

	@InjectMocks
	private EntityAuthorizationManagerImpl entityAuthManager;

	private UserInfo userInfo;

	private String entityId;
	private Long entityIdLong;
	private List<Long> entityIds;
	private UserEntityPermissionsState permissionsState;
	private Map<Long, UserEntityPermissionsState> mapIdToState;
	
	private UsersRestrictionStatus accessRestrictions;
	private Map<Long, UsersRestrictionStatus> mapIdToAccess;

	@BeforeEach
	public void before() {
		boolean isAdmin = false;
		userInfo = new UserInfo(isAdmin, 123L);
		userInfo.setAcceptsTermsOfUse(true);
		userInfo.getGroups().add(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId());
		userInfo.getGroups().add(userInfo.getId());
		userInfo.getGroups().add(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.PUBLIC_GROUP.getPrincipalId());
		entityId = "syn456";
		entityIdLong = KeyFactory.stringToKey(entityId);
		entityIds = KeyFactory.stringToKeySingletonList(entityId);

		permissionsState = new UserEntityPermissionsState(entityIdLong);
		mapIdToState = new LinkedHashMap<Long, UserEntityPermissionsState>();
		mapIdToState.put(entityIdLong, permissionsState);
		
		accessRestrictions = new UsersRestrictionStatus(entityIdLong, userInfo.getId());
		mapIdToAccess = new LinkedHashMap<Long, UsersRestrictionStatus>();
		mapIdToAccess.put(entityIdLong, accessRestrictions);
	}

	@Test
	public void testGetUserPermissionsForEntityWithNoPermissions() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		userInfo.setAcceptsTermsOfUse(false);
		userInfo.getGroups().remove(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId());
		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	
	@Test
	public void testGetUserPermissionsForEntityWithCertifiedUser() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		userInfo.setAcceptsTermsOfUse(false);
		userInfo.getGroups().add(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId());
		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setIsCertifiedUser(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanCreate() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasCreate(true);
		userInfo.setAcceptsTermsOfUse(true);
		userInfo.getGroups().add(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId());
		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setIsCertifiedUser(true);
		expected.setCanAddChild(true);
		expected.setCanCertifiedUserAddChild(true);
		expected.setCanUpload(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanCreateButNotCertified() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasCreate(true);
		userInfo.setAcceptsTermsOfUse(true);
		userInfo.getGroups().remove(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId());
		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setIsCertifiedUser(false);
		expected.setCanAddChild(false);
		expected.setCanCertifiedUserAddChild(true);
		expected.setCanUpload(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanChangePermission() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasChangePermissions(true);

		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setCanUpload(true);
		expected.setIsCertifiedUser(true);
		expected.setCanChangePermissions(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanChangeSettings() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasChangeSettings(true);

		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setCanUpload(true);
		expected.setIsCertifiedUser(true);
		expected.setCanChangeSettings(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanDelete() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasDelete(true);

		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setCanUpload(true);
		expected.setIsCertifiedUser(true);
		expected.setCanDelete(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanEdit() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasUpdate(true);

		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setCanUpload(true);
		expected.setIsCertifiedUser(true);
		expected.setCanEdit(true);
		expected.setCanCertifiedUserEdit(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanEditNotCertified() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasUpdate(true);
		userInfo.getGroups().remove(AuthorizationConstants.BOOTSTRAP_PRINCIPAL.CERTIFIED_USERS.getPrincipalId());

		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setCanUpload(true);
		expected.setIsCertifiedUser(false);
		expected.setCanEdit(false);
		expected.setCanCertifiedUserEdit(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanView() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasRead(true);

		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setCanUpload(true);
		expected.setIsCertifiedUser(true);
		expected.setCanView(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	@Test
	public void testGetUserPermissionsForEntityWithCanPublicRead() {
		when(mockUsersEntityPermissionsDao.getEntityPermissionsAsMap(any(), any())).thenReturn(mapIdToState);
		when(mockAccessRestrictionStatusDao.getEntityStatusAsMap(any(), any())).thenReturn(mapIdToAccess);
		permissionsState.withtDoesEntityExist(true);
		permissionsState.withHasPublicRead(true);

		// call under test
		UserEntityPermissions permissions = entityAuthManager.getUserPermissionsForEntity(userInfo, entityId);
		UserEntityPermissions expected = createAllFalseUserEntityPermissions();
		expected.setCanUpload(true);
		expected.setIsCertifiedUser(true);
		expected.setCanPublicRead(true);
		assertEquals(expected, permissions);
		
		verify(mockUsersEntityPermissionsDao).getEntityPermissionsAsMap(userInfo.getGroups(), entityIds);
		verify(mockAccessRestrictionStatusDao).getEntityStatusAsMap(entityIds, userInfo.getId());
	}
	
	
	public UserEntityPermissions createAllFalseUserEntityPermissions() {
		UserEntityPermissions up =  new UserEntityPermissions();
		up.setCanAddChild(false);
		up.setCanCertifiedUserAddChild(false);
		up.setCanCertifiedUserEdit(false);
		up.setCanChangePermissions(false);
		up.setCanChangeSettings(false);
		up.setCanDelete(false);
		up.setCanDownload(false);
		up.setCanEdit(false);
		up.setCanModerate(false);
		up.setCanPublicRead(false);
		up.setCanUpload(false);
		up.setCanView(false);
		up.setIsCertifiedUser(false);
		up.setCanEnableInheritance(false);
		up.setIsCertificationRequired(true);
		return up;
	}

}
