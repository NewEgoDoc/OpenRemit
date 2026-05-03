package com.openremit.common

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun `plus same currency adds amounts`() {
        val sum = Money.of("500", Currency.KRW) + Money.of("1000", Currency.KRW)
        assertEquals(BigDecimal("1500"), sum.amount)
    }

    @Test
    fun `minus same currency subtracts amounts`() {
        val diff = Money.of("1000", Currency.KRW) - Money.of("300", Currency.KRW)
        assertEquals(BigDecimal("700"), diff.amount)
    }

    @Test
    fun `plus different currency throws`() {
        assertFailsWith<IllegalArgumentException> {
            Money.of("500", Currency.KRW) + Money.of("100", Currency.USD)
        }
    }

    @Test
    fun `minus different currency throws`() {
        assertFailsWith<IllegalArgumentException> {
            Money.of("500", Currency.KRW) - Money.of("100", Currency.USD)
        }
    }

    @Test
    fun `compareTo different currency throws`() {
        assertFailsWith<IllegalArgumentException> {
            Money.of("500", Currency.KRW).compareTo(Money.of("100", Currency.USD))
        }
    }

    @Test
    fun `scale exceeding 4 throws`() {
        assertFailsWith<IllegalArgumentException> {
            Money(BigDecimal("1.12345"), Currency.KRW)
        }
    }

    @Test
    fun `compareTo orders amounts within same currency`() {
        assertTrue(Money.of("1000", Currency.KRW) > Money.of("500", Currency.KRW))
    }

    @Test
    fun `zero builds zero amount`() {
        val z = Money.zero(Currency.KRW)
        assertTrue(z.isZero)
        assertEquals(Currency.KRW, z.currency)
    }
}
