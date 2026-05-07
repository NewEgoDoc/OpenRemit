package com.openremit.reconcile.infrastructure.persistence

import com.openremit.reconcile.application.WalletSnapshot
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * 정산 잡이 wallet 정합성 검증을 위해 읽는 native 쿼리.
 *
 * reconciler 는 JPA 엔티티로 wallets / wallet_transactions 를 매핑하지 않는다 (DB 소유권 분리, ADR-011).
 * 매핑하면 엔티티 정의가 reconciler / remittance-api 에 중복되거나 결합되므로,
 * 정산 잡은 read-only native 쿼리로만 두 테이블에 접근한다.
 */
@Repository
class WalletReconcileQuery(
    private val entityManager: EntityManager,
) {

    fun loadSnapshots(): List<WalletSnapshot> {
        // wallet 마다 1행. 거래가 없으면 sum=0, last_balance_after=null.
        // last_balance_after: id 가 가장 큰 wallet_transactions.balance_after.
        val sql = """
            SELECT
                w.id                                                AS wallet_id,
                w.balance                                           AS balance,
                COALESCE(SUM(wt.amount), 0)                         AS sum_amount,
                (SELECT wt2.balance_after
                   FROM wallet_transactions wt2
                  WHERE wt2.wallet_id = w.id
                  ORDER BY wt2.id DESC
                  LIMIT 1)                                          AS last_balance_after
            FROM wallets w
            LEFT JOIN wallet_transactions wt ON wt.wallet_id = w.id
            GROUP BY w.id, w.balance
        """.trimIndent()

        @Suppress("UNCHECKED_CAST")
        val rows = entityManager.createNativeQuery(sql).resultList as List<Array<Any?>>
        return rows.map { row ->
            WalletSnapshot(
                walletId = (row[0] as Number).toLong(),
                balance = row[1] as BigDecimal,
                sumOfTransactions = row[2] as BigDecimal,
                lastBalanceAfter = row[3] as BigDecimal?,
            )
        }
    }
}
