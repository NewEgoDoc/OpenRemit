package com.openremit.common

import java.math.BigDecimal

data class Money(val amount: BigDecimal, val currency: Currency) : Comparable<Money> {

    init {
        require(amount.scale() <= SCALE) {
            "Money scale must be <= $SCALE, got ${amount.scale()}"
        }
    }

    operator fun plus(other: Money): Money {
        require(currency == other.currency) {
            "Currency mismatch: $currency vs ${other.currency}"
        }
        return Money(amount + other.amount, currency)
    }

    operator fun minus(other: Money): Money {
        require(currency == other.currency) {
            "Currency mismatch: $currency vs ${other.currency}"
        }
        return Money(amount - other.amount, currency)
    }

    override fun compareTo(other: Money): Int {
        require(currency == other.currency) {
            "Currency mismatch: $currency vs ${other.currency}"
        }
        return amount.compareTo(other.amount)
    }

    val isPositive: Boolean get() = amount.signum() > 0
    val isZero: Boolean get() = amount.signum() == 0

    companion object {
        const val SCALE = 4

        fun of(amount: String, currency: Currency): Money =
            Money(BigDecimal(amount), currency)

        fun zero(currency: Currency): Money =
            Money(BigDecimal.ZERO, currency)
    }
}
