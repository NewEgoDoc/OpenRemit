package com.openremit.reconcile.application

import java.math.BigDecimal

/**
 * 단일 wallet의 정산 입력 스냅샷.
 * - balance: 현재 wallet.balance
 * - sumOfTransactions: 해당 wallet의 모든 wallet_transactions.amount 합 (A-검증)
 * - lastBalanceAfter: 가장 최근 wallet_transaction의 balance_after, 거래 0건이면 null (B-검증)
 */
data class WalletSnapshot(
    val walletId: Long,
    val balance: BigDecimal,
    val sumOfTransactions: BigDecimal,
    val lastBalanceAfter: BigDecimal?,
)

data class WalletMismatch(
    val walletId: Long,
    val balance: BigDecimal,
    val sumOfTransactions: BigDecimal,
    val lastBalanceAfter: BigDecimal?,
    val violatesA: Boolean,
    val violatesB: Boolean,
)

data class ReconcileResult(
    val totalCount: Int,
    val mismatches: List<WalletMismatch>,
) {
    val mismatchCount: Int get() = mismatches.size
}
