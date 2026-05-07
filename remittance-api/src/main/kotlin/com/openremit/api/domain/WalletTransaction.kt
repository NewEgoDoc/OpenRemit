package com.openremit.api.domain

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

enum class WalletTransactionRefType { REMITTANCE, PAYMENT, REFUND }

@Entity
@Table(name = "wallet_transactions")
class WalletTransaction(
    @Column(name = "wallet_id", nullable = false)
    val walletId: Long,

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    val amount: BigDecimal,

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    val balanceAfter: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 20)
    val referenceType: WalletTransactionRefType,

    @Column(name = "reference_id", nullable = false)
    val referenceId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
}
