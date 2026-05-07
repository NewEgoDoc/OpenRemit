-- wallet_transactions: 지갑 잔액 변동 이력 (감사 + 정산 ledger)
-- amount: 양수=입금, 음수=출금
-- balance_after: 변동 직후 잔액 스냅샷 (정산 잡 B-검증용)
-- reference_type / reference_id: 변동 원인 (REMITTANCE/PAYMENT/REFUND)
-- 정산 잡 (reconciler):
--   A-검증: wallet.balance == Σ wallet_transactions.amount
--   B-검증: wallet.balance == 마지막 wallet_transactions.balance_after

CREATE TABLE wallet_transactions (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    wallet_id       BIGINT          NOT NULL,
    amount          DECIMAL(19, 4)  NOT NULL,
    balance_after   DECIMAL(19, 4)  NOT NULL,
    reference_type  VARCHAR(20)     NOT NULL,
    reference_id    BIGINT          NOT NULL,
    created_at      TIMESTAMP(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_wallet_transactions_wallet FOREIGN KEY (wallet_id) REFERENCES wallets(id),
    INDEX idx_wallet_transactions_wallet_created (wallet_id, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
