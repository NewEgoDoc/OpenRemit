-- Debezium MySQL connector 전용 read-only user (ADR-010)
-- /docker-entrypoint-initdb.d/ 에 마운트되어 mysql 초기 부팅 시 1회 실행됨.

CREATE USER IF NOT EXISTS 'debezium'@'%' IDENTIFIED BY 'dbz';
GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT
    ON *.* TO 'debezium'@'%';
FLUSH PRIVILEGES;
