package com.openremit.api.domain

import com.openremit.common.Currency
import com.openremit.common.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant

class InsufficientBalanceException(message: String) : RuntimeException(message)

@Entity
@Table(name = "wallets")
class Wallet(
    @Column(name = "user_id", nullable = false, unique = true)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    val currency: Currency,

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private var balanceAmount: BigDecimal = BigDecimal.ZERO.setScale(Money.SCALE),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Version
    var version: Long = 0

    val balance: Money
        get() = Money(balanceAmount, currency)

    fun deposit(amount: Money) {
        require(amount.currency == currency) {
            "Currency mismatch: wallet=$currency, deposit=${amount.currency}"
        }
        require(amount.isPositive) { "Deposit amount must be positive" }
        balanceAmount = balanceAmount + amount.amount
        updatedAt = Instant.now()
    }

    fun withdraw(amount: Money) {
        require(amount.currency == currency) {
            "Currency mismatch: wallet=$currency, withdraw=${amount.currency}"
        }
        require(amount.isPositive) { "Withdraw amount must be positive" }
        if (amount.amount > balanceAmount) {
            throw InsufficientBalanceException(
                "Insufficient balance: available=$balanceAmount, requested=${amount.amount}"
            )
        }
        balanceAmount = balanceAmount - amount.amount
        updatedAt = Instant.now()
    }
}
