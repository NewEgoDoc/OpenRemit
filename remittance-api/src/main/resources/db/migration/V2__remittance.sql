CREATE TABLE remittances (
    id                BIGINT          NOT NULL AUTO_INCREMENT,
    user_id           BIGINT          NOT NULL,
    from_currency     CHAR(3)         NOT NULL,
    from_amount       DECIMAL(19, 4)  NOT NULL,
    to_currency       CHAR(3)         NOT NULL,
    to_amount         DECIMAL(19, 4)  NOT NULL,
    fx_rate           DECIMAL(19, 8)  NOT NULL,
    receiver_name     VARCHAR(100)    NOT NULL,
    receiver_account  VARCHAR(100)    NOT NULL,
    status            VARCHAR(20)     NOT NULL,
    payment_id        BIGINT          NULL,
    payout_tx_id      VARCHAR(100)    NULL,
    failure_reason    VARCHAR(500)    NULL,
    cancel_reason     VARCHAR(500)    NULL,
    created_at        TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at        TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_remittances_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_remittances_user_status (user_id, status),
    INDEX idx_remittances_status_updated (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE remittance_status_history (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    remittance_id   BIGINT          NOT NULL,
    from_status     VARCHAR(20)     NOT NULL,
    to_status       VARCHAR(20)     NOT NULL,
    reason          VARCHAR(500)    NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_remittance_status_history_remittance
        FOREIGN KEY (remittance_id) REFERENCES remittances(id) ON DELETE CASCADE,
    INDEX idx_remittance_status_history_remittance (remittance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
