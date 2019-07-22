CREATE TABLE IF NOT EXISTS `FILES` (
  `ID` bigint(20) NOT NULL,
  `ETAG` char(36) NOT NULL,
  `PREVIEW_ID` bigint(20) DEFAULT NULL,
  `CREATED_ON` TIMESTAMP NOT NULL,
  `CREATED_BY` bigint(20) NOT NULL,
  `METADATA_TYPE` ENUM('S3', 'EXTERNAL', 'GOOGLE_CLOUD', 'PROXY', 'EXTERNAL_OBJ_STORE') NOT NULL,
  `CONTENT_TYPE` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `CONTENT_SIZE` bigint(20) DEFAULT NULL,
  `CONTENT_MD5` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `BUCKET_NAME` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `NAME` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `KEY` varchar(700) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `STORAGE_LOCATION_ID` bigint(20) DEFAULT NULL,
  `ENDPOINT` varchar(512) DEFAULT NULL,
  `IS_PREVIEW` boolean NOT NULL,
  PRIMARY KEY (`ID`),
  KEY `KEY_KEY` (`KEY`),
  KEY `MD5_KEY` (`CONTENT_MD5`),
  CONSTRAINT `PREVIEW_ID_FK` FOREIGN KEY (`PREVIEW_ID`) REFERENCES `FILES` (`ID`) ON DELETE SET NULL,
  CONSTRAINT `NO_PREVIEWS_OF_PREVIEWS` CHECK (IF(IS_PREVIEW, PREVIEW_ID is NULL, TRUE)),
  CONSTRAINT `FILE_CREATED_BY_FK` FOREIGN KEY (`CREATED_BY`) REFERENCES `JDOUSERGROUP` (`ID`) ON DELETE CASCADE,
  CONSTRAINT `FILE_STORAGE_LOCATION_ID_FK` FOREIGN KEY (`STORAGE_LOCATION_ID`) REFERENCES `STORAGE_LOCATION` (`ID`)
)
