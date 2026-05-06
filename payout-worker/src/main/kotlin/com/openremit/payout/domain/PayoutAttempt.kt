package com.openremit.payout.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "payout_attempts")
class PayoutAttempt(
    @Column(name = "remittance_id", nullable = false, unique = true)
    val remittanceId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PayoutAttemptStatus = PayoutAttemptStatus.PENDING

    @Column(name = "payout_tx_id", length = 100)
    var payoutTxId: String? = null

    @Column(name = "failure_reason", length = 500)
    var failureReason: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    fun markCompleted(payoutTxId: String) {
        this.payoutTxId = payoutTxId
        this.status = PayoutAttemptStatus.COMPLETED
        this.updatedAt = Instant.now()
    }

    fun markFailed(reason: String) {
        this.failureReason = reason
        this.status = PayoutAttemptStatus.FAILED
        this.updatedAt = Instant.now()
    }
}

enum class PayoutAttemptStatus {
    PENDING,
    COMPLETED,
    FAILED,
}
