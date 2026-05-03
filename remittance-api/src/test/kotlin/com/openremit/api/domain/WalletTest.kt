package com.openremit.api.domain

import com.openremit.common.Currency
import com.openremit.common.Money
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WalletTest {

    private fun newWallet(initial: String = "0", currency: Currency = Currency.KRW) =
        Wallet(userId = 1L, currency = currency).apply {
            if (initial != "0") deposit(Money.of(initial, currency))
        }

    @Test
    fun `new wallet starts at zero`() {
        val wallet = Wallet(userId = 1L, currency = Currency.KRW)
        assertEquals(BigDecimal.ZERO.setScale(Money.SCALE), wallet.balance.amount)
    }

    @Test
    fun `deposit increases balance`() {
        val wallet = newWallet(initial = "500")
        wallet.deposit(Money.of("1000", Currency.KRW))
        assertEquals(BigDecimal("1500.0000"), wallet.balance.amount)
    }

    @Test
    fun `withdraw reduces balance when sufficient`() {
        val wallet = newWallet(initial = "1000")
        wallet.withdraw(Money.of("300", Currency.KRW))
        assertEquals(BigDecimal("700.0000"), wallet.balance.amount)
    }

    @Test
    fun `withdraw exceeding balance throws`() {
        val wallet = newWallet(initial = "500")
        assertFailsWith<InsufficientBalanceException> {
            wallet.withdraw(Money.of("1000", Currency.KRW))
        }
    }

    @Test
    fun `deposit different currency throws`() {
        val wallet = newWallet(currency = Currency.KRW)
        assertFailsWith<IllegalArgumentException> {
            wallet.deposit(Money.of("100", Currency.USD))
        }
    }

    @Test
    fun `withdraw different currency throws`() {
        val wallet = newWallet(initial = "1000", currency = Currency.KRW)
        assertFailsWith<IllegalArgumentException> {
            wallet.withdraw(Money.of("100", Currency.USD))
        }
    }

    @Test
    fun `deposit zero or negative throws`() {
        val wallet = newWallet()
        assertFailsWith<IllegalArgumentException> {
            wallet.deposit(Money.zero(Currency.KRW))
        }
    }

    @Test
    fun `withdraw zero or negative throws`() {
        val wallet = newWallet(initial = "1000")
        assertFailsWith<IllegalArgumentException> {
            wallet.withdraw(Money.zero(Currency.KRW))
        }
    }
}
