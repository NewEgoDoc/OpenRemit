-- payout-worker 자체 소유 테이블 (ADR-011)
-- worker는 remittances 테이블에 일절 접근하지 않는다. 자체 멱등성/결과 outbox만 보유.

-- payout_attempts: 송금사 호출 시도 기록 + 멱등성 키
-- remittance_id UNIQUE → 동일 메시지 재소비 시 INSERT 실패로 중복 호출 차단
CREATE TABLE payout_attempts (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    remittance_id   BIGINT          NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    payout_tx_id    VARCHAR(100)    NULL,
    failure_reason  VARCHAR(500)    NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_payout_attempts_remittance (remittance_id),
    INDEX idx_payout_attempts_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- payout_outbox: worker → remittance-api 결과 이벤트 outbox
-- Debezium이 binlog → Kafka(remittance.payout.completed/failed) 발행.
CREATE TABLE payout_outbox (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    aggregate_id    VARCHAR(64)     NOT NULL,
    aggregate_type  VARCHAR(64)     NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         JSON            NOT NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_payout_outbox_aggregate (aggregate_type, aggregate_id),
    INDEX idx_payout_outbox_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
