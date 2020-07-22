CREATE TABLE IF NOT EXISTS `FEATURE_STATUS` (
  `ID` BIGINT NOT NULL,
  `ETAG` char(36) NOT NULL,
  `FEATURE_TYPE` enum('DATA_ACCESS_AUTO_REVOCATION', 'DATA_ACCESS_NOTIFICATIONS') NOT NULL,
  `ENABLED` BOOLEAN NOT NULL,
  PRIMARY KEY (`ID`),
  UNIQUE KEY `FEATURE_TYPE` (`FEATURE_TYPE`)
)
