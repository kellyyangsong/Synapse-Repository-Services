package org.sagebionetworks.repo.model.dbo.schema;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_ORGANIZATION_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_ORGANIZATION_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_ORGANIZATION;

import java.sql.Timestamp;

import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationDaoImp implements OrganizationDao {

	@Autowired
	DBOBasicDao basicDao;
	@Autowired
	IdGenerator idGenerator;
	@Autowired
	JdbcTemplate jdbcTemplate;

	static final RowMapper<DBOOrganization> ROW_MAPPER = new DBOOrganization().getTableMapping();

	@WriteTransaction
	@Override
	public Organization createOrganization(Organization org) {
		ValidateArgument.required(org, "Organization");
		ValidateArgument.required(org.getName(), "Organization.name");
		ValidateArgument.required(org.getCreatedBy(), "Organization.createdBy");
		DBOOrganization dbo = new DBOOrganization();
		dbo.setName(org.getName().toLowerCase());
		dbo.setCreatedBy(Long.parseLong(org.getCreatedBy()));
		dbo.setCreatedOn(new Timestamp(System.currentTimeMillis()));
		dbo.setId(idGenerator.generateNewId(IdType.ORGANIZATION_ID));
		basicDao.createNew(dbo);
		return getOrganization(dbo.getName());
	}

	@Override
	public Organization getOrganization(String name) {
		ValidateArgument.required(name, "name");
		try {
			DBOOrganization dbo = jdbcTemplate.queryForObject(
					"SELECT * FROM " + TABLE_ORGANIZATION + " WHERE " + COL_ORGANIZATION_NAME + " = ?", ROW_MAPPER,
					name.toLowerCase());
			return createDtoFromDbo(dbo);
		} catch (EmptyResultDataAccessException e) {
			throw new NotFoundException("Orgnaization with name: '"+name.toLowerCase()+"' not found");
		}
	}

	/**
	 * Create a DTO from the DBO.
	 * 
	 * @param dbo
	 * @return
	 */
	public static Organization createDtoFromDbo(DBOOrganization dbo) {
		Organization dto = new Organization();
		dto.setCreatedBy("" + dbo.getCreatedBy());
		dto.setCreatedOn(dbo.getCreatedOn());
		dto.setId(dbo.getId());
		dto.setName(dbo.getName());
		return dto;
	}

	@WriteTransaction
	@Override
	public void deleteOrganization(String name) {
		ValidateArgument.required(name, "name");
		int count = jdbcTemplate.update("DELETE FROM " + TABLE_ORGANIZATION + " WHERE " + COL_ORGANIZATION_NAME + " = ?",
				name.toLowerCase());
		if (count < 1) {
			throw new NotFoundException("Orgnaization with name: '"+name.toLowerCase()+"' not found");
		}
	}

	@Override
	public void truncateAll() {
		jdbcTemplate.update("DELETE FROM " + TABLE_ORGANIZATION + " WHERE " + COL_ORGANIZATION_ID + " > -1");
	}

}
