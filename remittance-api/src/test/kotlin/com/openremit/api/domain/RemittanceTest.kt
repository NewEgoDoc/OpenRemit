package com.openremit.api.domain

import com.openremit.common.Currency
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemittanceTest {

    private fun newRemittance() = Remittance(
        userId = 1L,
        fromCurrency = Currency.KRW,
        fromAmount = BigDecimal("100000.0000"),
        toCurrency = Currency.USD,
        toAmount = BigDecimal("73.5000"),
        fxRate = BigDecimal("0.00073500"),
        receiverName = "John Doe",
        receiverAccount = "1234-5678",
    )

    // ── 초기 상태 ────────────────────────────────────────────────

    @Test
    fun `new remittance starts in REQUESTED with empty history`() {
        val r = newRemittance()
        assertEquals(RemittanceStatus.REQUESTED, r.status)
        assertTrue(r.statusHistory.isEmpty())
        assertNull(r.paymentId)
        assertNull(r.payoutTxId)
    }

    // ── Valid 전이 ───────────────────────────────────────────────

    @Test
    fun `REQUESTED to PAID via markPaid`() {
        val r = newRemittance()
        r.markPaid(paymentId = 42L)
        assertEquals(RemittanceStatus.PAID, r.status)
        assertEquals(42L, r.paymentId)
    }

    @Test
    fun `REQUESTED to CANCELLED via cancel`() {
        val r = newRemittance()
        r.cancel(reason = "user requested")
        assertEquals(RemittanceStatus.CANCELLED, r.status)
        assertEquals("user requested", r.cancelReason)
    }

    @Test
    fun `PAID to PROCESSING via markProcessing`() {
        val r = newRemittance().apply { markPaid(42L) }
        r.markProcessing()
        assertEquals(RemittanceStatus.PROCESSING, r.status)
    }

    @Test
    fun `PAID to CANCELLED via cancel`() {
        val r = newRemittance().apply { markPaid(42L) }
        r.cancel(reason = "user changed mind")
        assertEquals(RemittanceStatus.CANCELLED, r.status)
    }

    @Test
    fun `PROCESSING to COMPLETED via markCompleted`() {
        val r = newRemittance().apply {
            markPaid(42L)
            markProcessing()
        }
        r.markCompleted(payoutTxId = "PAYOUT-XYZ")
        assertEquals(RemittanceStatus.COMPLETED, r.status)
        assertEquals("PAYOUT-XYZ", r.payoutTxId)
    }

    @Test
    fun `PROCESSING to FAILED via markFailed`() {
        val r = newRemittance().apply {
            markPaid(42L)
            markProcessing()
        }
        r.markFailed(reason = "bank rejected")
        assertEquals(RemittanceStatus.FAILED, r.status)
        assertEquals("bank rejected", r.failureReason)
    }

    // ── Invalid 전이 ─────────────────────────────────────────────

    @Test
    fun `REQUESTED cannot jump to PROCESSING`() {
        val r = newRemittance()
        val ex = assertFailsWith<IllegalStateTransitionException> { r.markProcessing() }
        assertEquals(RemittanceStatus.REQUESTED, ex.from)
        assertEquals(RemittanceStatus.PROCESSING, ex.to)
    }

    @Test
    fun `REQUESTED cannot jump to COMPLETED`() {
        assertFailsWith<IllegalStateTransitionException> {
            newRemittance().markCompleted("X")
        }
    }

    @Test
    fun `PROCESSING cannot be cancelled`() {
        val r = newRemittance().apply {
            markPaid(42L)
            markProcessing()
        }
        assertFailsWith<IllegalStateTransitionException> { r.cancel("too late") }
    }

    @Test
    fun `COMPLETED is terminal - no further transitions`() {
        val r = newRemittance().apply {
            markPaid(42L)
            markProcessing()
            markCompleted("PAYOUT-XYZ")
        }
        assertTrue(r.status.isTerminal)
        assertFailsWith<IllegalStateTransitionException> { r.cancel("nope") }
        assertFailsWith<IllegalStateTransitionException> { r.markFailed("nope") }
    }

    @Test
    fun `FAILED is terminal`() {
        val r = newRemittance().apply {
            markPaid(42L)
            markProcessing()
            markFailed("bank rejected")
        }
        assertTrue(r.status.isTerminal)
        assertFailsWith<IllegalStateTransitionException> { r.markCompleted("X") }
    }

    @Test
    fun `CANCELLED is terminal`() {
        val r = newRemittance().apply { cancel("user") }
        assertTrue(r.status.isTerminal)
        assertFailsWith<IllegalStateTransitionException> { r.markPaid(42L) }
    }

    @Test
    fun `cannot mark paid twice`() {
        val r = newRemittance().apply { markPaid(42L) }
        assertFailsWith<IllegalStateTransitionException> { r.markPaid(43L) }
    }

    // ── History 자동 기록 ────────────────────────────────────────

    @Test
    fun `each transition appends a status history entry`() {
        val r = newRemittance()
        r.markPaid(42L)
        r.markProcessing()
        r.markCompleted("PAYOUT-XYZ")

        val history = r.statusHistory
        assertEquals(3, history.size)

        assertEquals(RemittanceStatus.REQUESTED, history[0].fromStatus)
        assertEquals(RemittanceStatus.PAID, history[0].toStatus)

        assertEquals(RemittanceStatus.PAID, history[1].fromStatus)
        assertEquals(RemittanceStatus.PROCESSING, history[1].toStatus)

        assertEquals(RemittanceStatus.PROCESSING, history[2].fromStatus)
        assertEquals(RemittanceStatus.COMPLETED, history[2].toStatus)
    }

    @Test
    fun `history captures reason`() {
        val r = newRemittance()
        r.cancel(reason = "duplicate request")
        val entry = r.statusHistory.single()
        assertEquals("duplicate request", entry.reason)
    }

    @Test
    fun `failed transition does not append history`() {
        val r = newRemittance()
        runCatching { r.markCompleted("X") }
        assertTrue(r.statusHistory.isEmpty())
        assertEquals(RemittanceStatus.REQUESTED, r.status)
    }

    @Test
    fun `updatedAt advances on transition`() {
        val r = newRemittance()
        val before = r.updatedAt
        Thread.sleep(2)
        r.markPaid(42L)
        assertNotNull(r.updatedAt)
        assertTrue(r.updatedAt > before)
    }
}
