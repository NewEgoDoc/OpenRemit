package com.openremit.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReceiverInfoTest {

    @Test
    fun `valid name and account succeeds`() {
        val info = ReceiverInfo(name = "John Doe", account = "1234-5678")
        assertEquals("John Doe", info.name)
        assertEquals("1234-5678", info.account)
    }

    @Test
    fun `blank name throws`() {
        assertFailsWith<IllegalArgumentException> {
            ReceiverInfo(name = "  ", account = "1234")
        }
    }

    @Test
    fun `blank account throws`() {
        assertFailsWith<IllegalArgumentException> {
            ReceiverInfo(name = "John", account = "")
        }
    }

    @Test
    fun `name exceeding 100 chars throws`() {
        assertFailsWith<IllegalArgumentException> {
            ReceiverInfo(name = "x".repeat(101), account = "1234")
        }
    }

    @Test
    fun `account exceeding 100 chars throws`() {
        assertFailsWith<IllegalArgumentException> {
            ReceiverInfo(name = "John", account = "1".repeat(101))
        }
    }
}
