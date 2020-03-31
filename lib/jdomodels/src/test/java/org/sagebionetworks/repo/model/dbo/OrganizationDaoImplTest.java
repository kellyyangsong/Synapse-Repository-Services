package org.sagebionetworks.repo.model.dbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.dbo.schema.OrganizationDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class OrganizationDaoImplTest {

	@Autowired
	OrganizationDao organizationDao;

	Long adminUserId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId();

	@Test
	public void testCreateGetDelete() {
		Organization org = new Organization();
		org.setName("Foo.Bar");
		org.setCreatedBy("" + adminUserId);
		// Call under test
		Organization created = organizationDao.createOrganization(org);
		assertNotNull(created);
		// name should be lower
		assertEquals(org.getName().toLowerCase(), created.getName());
		assertNotNull(created.getId());
		assertNotNull(created.getCreatedOn());
		assertEquals(org.getCreatedBy(), created.getCreatedBy());

		// call under test
		Organization fetched = organizationDao.getOrganization(org.getName());
		assertEquals(created, fetched);
		// call under test
		organizationDao.deleteOrganization(org.getName());
	}

	@Test
	public void testGetOrganizationNotFound() {
		String name = "Foo.Bar";
		String message = assertThrows(NotFoundException.class, () -> {
			// call under test
			organizationDao.getOrganization(name);
		}).getMessage();
		assertEquals("Orgnaization with name: 'foo.bar' not found", message);
	}
	
	@Test
	public void testDeleteOrganizationNotFound() {
		String name = "Foo.Bar";
		String message = assertThrows(NotFoundException.class, () -> {
			// call under test
			organizationDao.deleteOrganization(name);
		}).getMessage();
		assertEquals("Orgnaization with name: 'foo.bar' not found", message);
	}

	@AfterEach
	public void afterEach() {
		organizationDao.truncateAll();
	}
}
