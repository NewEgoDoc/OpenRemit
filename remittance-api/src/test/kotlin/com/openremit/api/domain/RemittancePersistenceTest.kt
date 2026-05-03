package com.openremit.api.domain

import com.openremit.api.TestcontainersConfig
import com.openremit.api.infrastructure.persistence.RemittanceRepository
import com.openremit.api.infrastructure.persistence.UserRepository
import com.openremit.common.Currency
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfig::class)
@Transactional
class RemittancePersistenceTest @Autowired constructor(
    private val remittanceRepository: RemittanceRepository,
    private val userRepository: UserRepository,
) {

    @AfterTest
    fun cleanup() {
        remittanceRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    @Test
    fun `saving remittance with status history persists FK correctly`() {
        val user = userRepository.saveAndFlush(
            com.openremit.api.domain.User(
                email = "rp@example.com",
                passwordHash = "hash",
                name = "RP",
            )
        )
        val r = Remittance(
            userId = user.id,
            fromCurrency = Currency.KRW,
            fromAmount = BigDecimal("100000.0000"),
            toCurrency = Currency.USD,
            toAmount = BigDecimal("73.5000"),
            fxRate = BigDecimal("0.00073500"),
            receiverName = "John Doe",
            receiverAccount = "1234-5678",
        )
        r.markPaid(paymentId = 42L)
        r.markProcessing()

        val saved = remittanceRepository.saveAndFlush(r)

        val reloaded = remittanceRepository.findById(saved.id).orElseThrow()
        assertEquals(RemittanceStatus.PROCESSING, reloaded.status)
        assertEquals(2, reloaded.statusHistory.size)
        assertEquals(RemittanceStatus.REQUESTED, reloaded.statusHistory[0].fromStatus)
        assertEquals(RemittanceStatus.PAID, reloaded.statusHistory[0].toStatus)
        assertEquals(RemittanceStatus.PAID, reloaded.statusHistory[1].fromStatus)
        assertEquals(RemittanceStatus.PROCESSING, reloaded.statusHistory[1].toStatus)
        assertEquals(reloaded.id, reloaded.statusHistory[0].remittance.id)
    }
}
