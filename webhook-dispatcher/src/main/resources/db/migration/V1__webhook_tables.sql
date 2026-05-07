-- webhook-dispatcher 자체 소유 테이블 (ADR-011)
-- dispatcher는 remittances 테이블에 일절 접근하지 않는다. 자체 발송 이력만 보유.

-- webhooks: 발송 시도 + 재시도 스케줄 + 멱등성 키
-- event_id UNIQUE → Kafka 중복 소비 시 INSERT 실패로 중복 발송 차단
-- (status, next_retry_at) 인덱스 → 폴링 스케줄러가 due 행 효율적으로 조회
CREATE TABLE webhooks (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    event_id        VARCHAR(100)    NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    target_url      VARCHAR(500)    NOT NULL,
    payload         JSON            NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    attempt_count   INT             NOT NULL DEFAULT 0,
    next_retry_at   TIMESTAMP(6)    NULL,
    last_response_status INT        NULL,
    last_failure_reason  VARCHAR(500) NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_webhooks_event_id (event_id),
    INDEX idx_webhooks_status_next_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
