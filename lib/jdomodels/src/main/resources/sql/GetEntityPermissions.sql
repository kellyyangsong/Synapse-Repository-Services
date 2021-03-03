/*
 * For each entity, the RECURSIVE BEN table will contain one row for the entity plus an additional row for each entity in its
 * hierarchy until an ACL is found.  Note: Only the last row with an ACL_ID will have a correct benefactorId.  The finally
 * BEN table will only contain the last valid row for each entity since all rows with null ACL ids are filtered out.
 * 
 * The ACC table will contain one row for each distinct permission that the user has been granted to any of their principals
 * on the ACL identified from the BEN table.
 * 
 * The PUB table will contain one row for each ACL that grants the READ permission to PUBLIC.  
 *
 * The final table pivots each row from the ACC table to a column by grouping on the entity id.
 */
WITH
	BEN AS (
		WITH RECURSIVE BEN (ENTITY_ID, PARENT_ID, BENEFACTOR_ID, ACL_ID, DEPTH) AS
			(
				SELECT N.ID, N.PARENT_ID, N.ID AS BENEFACTOR_ID, A.ID AS ACL_ID, 1 AS DEPTH
					FROM JDONODE N LEFT JOIN ACL A ON (N.ID = A.OWNER_ID AND A.OWNER_TYPE = 'ENTITY')
					WHERE N.ID IN (:entityIds)
				UNION DISTINCT
				SELECT BEN.ENTITY_ID, N.PARENT_ID, N.ID AS BENEFACTOR_ID,
					A.ID AS ACL_ID, BEN.DEPTH + 1 AS DEPTH 
					FROM BEN JOIN JDONODE N ON (BEN.PARENT_ID = N.ID) 
					LEFT JOIN ACL A ON (N.ID = A.OWNER_ID AND A.OWNER_TYPE = 'ENTITY')
					WHERE BEN.ACL_ID IS NULL AND DEPTH < :depth
			)
		SELECT ENTITY_ID, BENEFACTOR_ID, ACL_ID FROM BEN WHERE ACL_ID IS NOT NULL
	),
	ACC AS (
		SELECT DISTINCT RA.OWNER_ID AS ACL_ID, RAT.STRING_ELE AS ACCESS_TYPE
			FROM BEN JOIN JDORESOURCEACCESS RA ON (BEN.ACL_ID = RA.OWNER_ID)
			JOIN JDORESOURCEACCESS_ACCESSTYPE RAT ON (RA.OWNER_ID = RAT.OWNER_ID)
			WHERE RA.GROUP_ID IN (:usersGroups)
	),
	PUB AS (
		SELECT DISTINCT RA.OWNER_ID AS ACL_ID, RAT.STRING_ELE AS ACCESS_TYPE
			FROM BEN JOIN JDORESOURCEACCESS RA ON (BEN.ACL_ID = RA.OWNER_ID)
			JOIN JDORESOURCEACCESS_ACCESSTYPE RAT ON (RA.OWNER_ID = RAT.OWNER_ID)
			WHERE RA.GROUP_ID = :publicId AND RAT.STRING_ELE = 'READ'
	)
SELECT 
 BEN.ENTITY_ID,
 MAX(N.NODE_TYPE) AS ENTITY_TYPE,
 MAX(N.PARENT_ID) AS ENTITY_PARENT_ID,
 MAX(N.CREATED_BY) AS ENTITY_CREATED_BY,
 MAX(BEN.BENEFACTOR_ID) AS BENEFACTOR_ID,
 MAX(DT.DATA_TYPE) AS DATA_TYPE,
 COUNT(CASE WHEN ACC.ACCESS_TYPE = 'CHANGE_PERMISSIONS' THEN 1 END) AS CHANGE_PERMISSIONS_COUNT,
 COUNT(CASE WHEN ACC.ACCESS_TYPE = 'CHANGE_SETTINGS' THEN 1 END) AS CHANGE_SETTINGS_COUNT,
 COUNT(CASE WHEN ACC.ACCESS_TYPE = 'CREATE' THEN 1 END) AS CREATE_COUNT,
 COUNT(CASE WHEN ACC.ACCESS_TYPE = 'UPDATE' THEN 1 END) AS UPDATE_COUNT,
 COUNT(CASE WHEN ACC.ACCESS_TYPE = 'DELETE' THEN 1 END) AS DELETE_COUNT,
 COUNT(CASE WHEN ACC.ACCESS_TYPE = 'DOWNLOAD' THEN 1 END) AS DOWNLOAD_COUNT,
 COUNT(CASE WHEN ACC.ACCESS_TYPE = 'READ' THEN 1 END) AS READ_COUNT,
 COUNT(CASE WHEN ACC.ACCESS_TYPE = 'MODERATE' THEN 1 END) AS MODERATE_COUNT,
 COUNT(CASE WHEN PUB.ACCESS_TYPE = 'READ' THEN 1 END) AS PUBLIC_READ_COUNT
 	FROM BEN
 	JOIN JDONODE N ON (BEN.ENTITY_ID = N.ID)
 	LEFT JOIN ACC ON (BEN.ACL_ID = ACC.ACL_ID)
 	LEFT JOIN PUB ON (BEN.ACL_ID = PUB.ACL_ID)
    LEFT JOIN DATA_TYPE DT ON (BEN.ENTITY_ID = DT.OBJECT_ID AND DT.OBJECT_TYPE = 'ENTITY')
    GROUP BY BEN.ENTITY_ID