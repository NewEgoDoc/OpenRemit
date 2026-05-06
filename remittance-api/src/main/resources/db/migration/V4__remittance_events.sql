-- Outbox 테이블 (ADR-003 + ADR-010)
-- Debezium MySQL connector + Outbox Event Router SMT가 binlog → Kafka 발행.
-- aggregate_id: remittance_id (Kafka key로 매핑)
-- event_type: 'remittance.paid' 등 — Debezium SMT가 토픽명으로 사용
-- payload: JSON 직렬화된 이벤트 본문

CREATE TABLE remittance_events (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    aggregate_id    VARCHAR(64)     NOT NULL,
    aggregate_type  VARCHAR(64)     NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    payload         JSON            NOT NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_remittance_events_aggregate (aggregate_type, aggregate_id),
    INDEX idx_remittance_events_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
