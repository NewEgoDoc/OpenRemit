-- reconciler 자체 소유 테이블 (ADR-011 패턴)
-- 정산 결과 저장. wallets/wallet_transactions 는 read-only로만 접근.

CREATE TABLE reconciliations (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    target_date     DATE            NOT NULL,
    total_count     INT             NOT NULL,
    mismatch_count  INT             NOT NULL,
    details         JSON            NOT NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_reconciliations_target_date (target_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
