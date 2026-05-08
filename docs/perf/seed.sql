-- Day 11 인덱스 튜닝 측정용 시드 데이터
-- 운영 마이그레이션이 아니라 측정 전용. Flyway에 포함되지 않음.
--
-- 사용법 (root 권한 필요 — sql_log_bin 제어):
--   docker exec -i openremit-mysql mysql -uroot -prootpw openremit < OpenRemit/docs/perf/seed.sql
--
-- 결과:
--   users        +10,000  (email LIKE 'seed-user-%@example.com')
--   wallets      +10,000  (시드 유저당 KRW 지갑 1개)
--   remittances  +500,000 (시드 유저당 50건, 균등 분배)
--
-- 주의:
--   sql_log_bin=0 으로 적재하므로 Debezium에는 이 데이터가 보이지 않습니다.
--   측정 후 정리:
--     docker exec openremit-mysql mysql -uroot -prootpw openremit -e "
--       SET sql_log_bin = 0;
--       DELETE FROM remittances WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'seed-user-%@example.com');
--       DELETE FROM wallets    WHERE user_id IN (SELECT id FROM users WHERE email LIKE 'seed-user-%@example.com');
--       DELETE FROM users      WHERE email LIKE 'seed-user-%@example.com';"

SET @@SESSION.sql_log_bin = 0;
SET @@SESSION.foreign_key_checks = 0;
SET @@SESSION.unique_checks = 0;
SET @@SESSION.cte_max_recursion_depth = 1000000;

-- 1) 시퀀스 임시 테이블 — 1..500000 dense
DROP TABLE IF EXISTS _seq;
CREATE TABLE _seq (n BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO _seq (n)
WITH RECURSIVE r(i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM r WHERE i < 500000
)
SELECT i FROM r;

-- 2) USERS — 10,000명
INSERT INTO users (email, password_hash, name)
SELECT
  CONCAT('seed-user-', n, '@example.com'),
  '$2a$10$dummyhashdummyhashdummyhashdummyhashdummyhashdumm',
  CONCAT('Seed User ', n)
FROM _seq
WHERE n <= 10000;

SET @user_min := (SELECT MIN(id) FROM users WHERE email LIKE 'seed-user-%@example.com');

-- 3) WALLETS — 시드 유저당 KRW 지갑 1개
INSERT INTO wallets (user_id, currency, balance, version)
SELECT id, 'KRW', 1000000.0000, 0
FROM users
WHERE email LIKE 'seed-user-%@example.com';

-- 4) REMITTANCES — 100,000건씩 5회 chunk INSERT (redo log 회수 유도)
--   user_id 분배: @user_min + (n % 10000) → 사용자당 정확히 50건
--   created_at 분포: 결정적 큰-소수 곱으로 1년 범위에 의사난수 분산
INSERT INTO remittances
  (user_id, from_currency, from_amount, to_currency, to_amount, fx_rate,
   receiver_name, receiver_account, status, created_at, updated_at)
SELECT
  @user_min + (n % 10000),
  'KRW', 10000.0000,
  'USD', 7.5000, 0.00075000,
  CONCAT('Receiver ', n), CONCAT('ACC-', n),
  ELT(1 + (n % 6), 'REQUESTED', 'PAID', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND)
FROM _seq WHERE n BETWEEN 1 AND 100000;

INSERT INTO remittances
  (user_id, from_currency, from_amount, to_currency, to_amount, fx_rate,
   receiver_name, receiver_account, status, created_at, updated_at)
SELECT
  @user_min + (n % 10000),
  'KRW', 10000.0000,
  'USD', 7.5000, 0.00075000,
  CONCAT('Receiver ', n), CONCAT('ACC-', n),
  ELT(1 + (n % 6), 'REQUESTED', 'PAID', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND)
FROM _seq WHERE n BETWEEN 100001 AND 200000;

INSERT INTO remittances
  (user_id, from_currency, from_amount, to_currency, to_amount, fx_rate,
   receiver_name, receiver_account, status, created_at, updated_at)
SELECT
  @user_min + (n % 10000),
  'KRW', 10000.0000,
  'USD', 7.5000, 0.00075000,
  CONCAT('Receiver ', n), CONCAT('ACC-', n),
  ELT(1 + (n % 6), 'REQUESTED', 'PAID', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND)
FROM _seq WHERE n BETWEEN 200001 AND 300000;

INSERT INTO remittances
  (user_id, from_currency, from_amount, to_currency, to_amount, fx_rate,
   receiver_name, receiver_account, status, created_at, updated_at)
SELECT
  @user_min + (n % 10000),
  'KRW', 10000.0000,
  'USD', 7.5000, 0.00075000,
  CONCAT('Receiver ', n), CONCAT('ACC-', n),
  ELT(1 + (n % 6), 'REQUESTED', 'PAID', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND)
FROM _seq WHERE n BETWEEN 300001 AND 400000;

INSERT INTO remittances
  (user_id, from_currency, from_amount, to_currency, to_amount, fx_rate,
   receiver_name, receiver_account, status, created_at, updated_at)
SELECT
  @user_min + (n % 10000),
  'KRW', 10000.0000,
  'USD', 7.5000, 0.00075000,
  CONCAT('Receiver ', n), CONCAT('ACC-', n),
  ELT(1 + (n % 6), 'REQUESTED', 'PAID', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED'),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND),
  DATE_SUB(NOW(6), INTERVAL ((n * 1234577) MOD 31536000) SECOND)
FROM _seq WHERE n BETWEEN 400001 AND 500000;

DROP TABLE _seq;

-- 옵티마이저 통계 갱신
ANALYZE TABLE users, wallets, remittances;

-- 검증
SELECT 'users seed'         AS t, COUNT(*) AS cnt FROM users WHERE email LIKE 'seed-user-%@example.com'
UNION ALL SELECT 'wallets total',     COUNT(*) FROM wallets
UNION ALL SELECT 'remittances total', COUNT(*) FROM remittances;
