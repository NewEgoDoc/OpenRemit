CREATE TABLE payments (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    user_id         BIGINT          NOT NULL,
    amount          DECIMAL(19, 4)  NOT NULL,
    currency        CHAR(3)         NOT NULL,
    method          VARCHAR(20)     NOT NULL,
    status          VARCHAR(30)     NOT NULL,
    external_tx_id  VARCHAR(100)    NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_payments_user FOREIGN KEY (user_id) REFERENCES users(id),
    INDEX idx_payments_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE idempotency_keys (
    idempotency_key  VARCHAR(100)    NOT NULL,
    user_id          BIGINT          NOT NULL,
    endpoint         VARCHAR(100)    NOT NULL,
    request_hash     VARCHAR(64)     NOT NULL,
    response_body    LONGTEXT        NOT NULL,
    http_status      INT             NOT NULL,
    expires_at       TIMESTAMP(6)    NOT NULL,
    created_at       TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (idempotency_key),
    INDEX idx_idempotency_keys_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
