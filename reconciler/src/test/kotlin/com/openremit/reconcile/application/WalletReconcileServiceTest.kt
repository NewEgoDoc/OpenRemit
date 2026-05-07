package com.openremit.reconcile.application

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WalletReconcileServiceTest {

    private val service = WalletReconcileService()

    @Test
    fun `consistent wallet produces no mismatch`() {
        val result = service.reconcile(
            listOf(snapshot(1L, balance = "900", sum = "900", last = "900"))
        )
        assertEquals(1, result.totalCount)
        assertEquals(0, result.mismatchCount)
    }

    @Test
    fun `wallet with zero balance and no transactions is consistent`() {
        val result = service.reconcile(
            listOf(snapshot(1L, balance = "0", sum = "0", last = null))
        )
        assertEquals(0, result.mismatchCount)
    }

    @Test
    fun `A-only violation — sum differs from balance but last balance_after matches`() {
        // ledger의 중간 row 1건이 누락되어 합은 안 맞지만 마지막 balance_after는 우연히 일치
        val result = service.reconcile(
            listOf(snapshot(1L, balance = "900", sum = "800", last = "900"))
        )
        assertEquals(1, result.mismatchCount)
        val m = result.mismatches.single()
        assertTrue(m.violatesA, "A must be violated")
        assertFalse(m.violatesB, "B must not be violated")
    }

    @Test
    fun `B-only violation — sum matches but last balance_after differs`() {
        // ledger 합은 맞지만 마지막 row의 balance_after 가 잘못 기록됨 (또는 race로 손실)
        val result = service.reconcile(
            listOf(snapshot(1L, balance = "900", sum = "900", last = "800"))
        )
        assertEquals(1, result.mismatchCount)
        val m = result.mismatches.single()
        assertFalse(m.violatesA)
        assertTrue(m.violatesB)
    }

    @Test
    fun `both A and B violated — typical data corruption`() {
        val result = service.reconcile(
            listOf(snapshot(1L, balance = "900", sum = "800", last = "800"))
        )
        val m = result.mismatches.single()
        assertTrue(m.violatesA)
        assertTrue(m.violatesB)
    }

    @Test
    fun `no transactions but non-zero balance — B violated, A also violated`() {
        // ledger 가 0건인데 잔액이 있음 → 둘 다 잡힘
        val result = service.reconcile(
            listOf(snapshot(1L, balance = "900", sum = "0", last = null))
        )
        val m = result.mismatches.single()
        assertTrue(m.violatesA)
        assertTrue(m.violatesB)
    }

    @Test
    fun `mixed wallets — only inconsistent ones reported`() {
        val result = service.reconcile(
            listOf(
                snapshot(1L, balance = "100", sum = "100", last = "100"),  // ok
                snapshot(2L, balance = "200", sum = "150", last = "200"),  // A violated
                snapshot(3L, balance = "300", sum = "300", last = "300"),  // ok
                snapshot(4L, balance = "400", sum = "400", last = "350"),  // B violated
            )
        )
        assertEquals(4, result.totalCount)
        assertEquals(2, result.mismatchCount)
        assertEquals(setOf(2L, 4L), result.mismatches.map { it.walletId }.toSet())
    }

    @Test
    fun `decimal scale differences are not treated as mismatch`() {
        // 1000.0000 vs 1000 — compareTo는 0이어야 한다
        val result = service.reconcile(
            listOf(
                snapshot(1L, balance = "1000.0000", sum = "1000", last = "1000.00")
            )
        )
        assertEquals(0, result.mismatchCount)
    }

    private fun snapshot(id: Long, balance: String, sum: String, last: String?) =
        WalletSnapshot(
            walletId = id,
            balance = BigDecimal(balance),
            sumOfTransactions = BigDecimal(sum),
            lastBalanceAfter = last?.let { BigDecimal(it) },
        )
}
