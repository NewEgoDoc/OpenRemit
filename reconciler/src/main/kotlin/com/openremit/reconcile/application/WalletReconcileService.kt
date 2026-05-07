package com.openremit.reconcile.application

import org.springframework.stereotype.Service

/**
 * 정산 검증 도메인 서비스.
 *
 * A-검증: wallet.balance == Σ wallet_transactions.amount
 *   - "총합 무결성" — 누락/오기록된 ledger row를 잡는다.
 *
 * B-검증: wallet.balance == 마지막 wallet_transaction.balance_after
 *   - "마지막 갱신 무결성" — race로 인한 last-write 손실, 트랜잭션 외부에서 일어난
 *     balance 직접 수정을 잡는다. 거래가 0건이면 balance 도 0이어야 한다.
 *
 * 두 검증 중 하나라도 위반하면 mismatch.
 */
@Service
class WalletReconcileService {

    fun reconcile(snapshots: List<WalletSnapshot>): ReconcileResult {
        val mismatches = snapshots.mapNotNull { evaluate(it) }
        return ReconcileResult(totalCount = snapshots.size, mismatches = mismatches)
    }

    private fun evaluate(s: WalletSnapshot): WalletMismatch? {
        val violatesA = s.balance.compareTo(s.sumOfTransactions) != 0
        val violatesB = if (s.lastBalanceAfter == null) {
            s.balance.signum() != 0
        } else {
            s.balance.compareTo(s.lastBalanceAfter) != 0
        }
        if (!violatesA && !violatesB) return null
        return WalletMismatch(
            walletId = s.walletId,
            balance = s.balance,
            sumOfTransactions = s.sumOfTransactions,
            lastBalanceAfter = s.lastBalanceAfter,
            violatesA = violatesA,
            violatesB = violatesB,
        )
    }
}
