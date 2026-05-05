package com.openremit.payment

import com.openremit.common.Currency
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

enum class PaymentStatus { APPROVED, CANCELLED, PARTIAL_CANCELLED }

enum class PaymentMethod { CARD, BANK_TRANSFER }

@Entity
@Table(name = "payments")
class Payment(
    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    val currency: Currency,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val method: PaymentMethod,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var status: PaymentStatus = PaymentStatus.APPROVED

    @Column(name = "external_tx_id", length = 100)
    var externalTxId: String? = null
}
