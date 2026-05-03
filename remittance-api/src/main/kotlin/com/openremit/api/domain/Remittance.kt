package com.openremit.api.domain

import com.openremit.common.Currency
import com.openremit.common.ReceiverInfo
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "remittances")
class Remittance(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "from_currency", nullable = false, length = 3)
    val fromCurrency: Currency,

    @Column(name = "from_amount", nullable = false, precision = 19, scale = 4)
    val fromAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_currency", nullable = false, length = 3)
    val toCurrency: Currency,

    @Column(name = "to_amount", nullable = false, precision = 19, scale = 4)
    val toAmount: BigDecimal,

    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8)
    val fxRate: BigDecimal,

    @Column(name = "receiver_name", nullable = false, length = 100)
    val receiverName: String,

    @Column(name = "receiver_account", nullable = false, length = 100)
    val receiverAccount: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: RemittanceStatus = RemittanceStatus.REQUESTED

    @Column(name = "payment_id")
    var paymentId: Long? = null

    @Column(name = "payout_tx_id", length = 100)
    var payoutTxId: String? = null

    @Column(name = "failure_reason", length = 500)
    var failureReason: String? = null

    @Column(name = "cancel_reason", length = 500)
    var cancelReason: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "remittance_id", nullable = false)
    @OrderBy("id ASC")
    private val _statusHistory: MutableList<RemittanceStatusHistory> = mutableListOf()

    val statusHistory: List<RemittanceStatusHistory>
        get() = _statusHistory.toList()

    val receiverInfo: ReceiverInfo
        get() = ReceiverInfo(receiverName, receiverAccount)

    fun markPaid(paymentId: Long) {
        ensureCanTransitionTo(RemittanceStatus.PAID)
        this.paymentId = paymentId
        transition(RemittanceStatus.PAID, "Payment $paymentId approved")
    }

    fun markProcessing() {
        ensureCanTransitionTo(RemittanceStatus.PROCESSING)
        transition(RemittanceStatus.PROCESSING, "Queued for payout")
    }

    fun markCompleted(payoutTxId: String) {
        ensureCanTransitionTo(RemittanceStatus.COMPLETED)
        this.payoutTxId = payoutTxId
        transition(RemittanceStatus.COMPLETED, "Payout completed: $payoutTxId")
    }

    fun markFailed(reason: String) {
        ensureCanTransitionTo(RemittanceStatus.FAILED)
        this.failureReason = reason
        transition(RemittanceStatus.FAILED, reason)
    }

    fun cancel(reason: String) {
        ensureCanTransitionTo(RemittanceStatus.CANCELLED)
        this.cancelReason = reason
        transition(RemittanceStatus.CANCELLED, reason)
    }

    private fun ensureCanTransitionTo(target: RemittanceStatus) {
        val allowed = ALLOWED_TRANSITIONS[status].orEmpty()
        if (target !in allowed) {
            throw IllegalStateTransitionException(status, target)
        }
    }

    private fun transition(target: RemittanceStatus, reason: String) {
        val from = status
        status = target
        updatedAt = Instant.now()
        _statusHistory += RemittanceStatusHistory(
            fromStatus = from,
            toStatus = target,
            reason = reason,
        )
    }

    companion object {
        private val ALLOWED_TRANSITIONS: Map<RemittanceStatus, Set<RemittanceStatus>> = mapOf(
            RemittanceStatus.REQUESTED to setOf(RemittanceStatus.PAID, RemittanceStatus.CANCELLED),
            RemittanceStatus.PAID to setOf(RemittanceStatus.PROCESSING, RemittanceStatus.CANCELLED),
            RemittanceStatus.PROCESSING to setOf(RemittanceStatus.COMPLETED, RemittanceStatus.FAILED),
            RemittanceStatus.COMPLETED to emptySet(),
            RemittanceStatus.FAILED to emptySet(),
            RemittanceStatus.CANCELLED to emptySet(),
        )
    }
}
