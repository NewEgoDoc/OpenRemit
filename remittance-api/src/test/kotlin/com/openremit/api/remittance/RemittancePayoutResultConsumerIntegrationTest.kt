package com.openremit.api.remittance

import com.openremit.api.TestcontainersConfig
import com.openremit.api.domain.Remittance
import com.openremit.api.domain.RemittanceStatus
import com.openremit.api.domain.User
import com.openremit.api.domain.Wallet
import com.openremit.api.infrastructure.persistence.RemittanceRepository
import com.openremit.api.infrastructure.persistence.UserRepository
import com.openremit.api.infrastructure.persistence.WalletRepository
import com.openremit.common.Currency
import com.openremit.common.Money
import com.openremit.common.events.RemittanceEventTopics
import com.openremit.common.events.RemittancePayoutCompletedEvent
import com.openremit.common.events.RemittancePayoutFailedEvent
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@Import(TestcontainersConfig::class)
class RemittancePayoutResultConsumerIntegrationTest @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val userRepository: UserRepository,
    private val walletRepository: WalletRepository,
    private val remittanceRepository: RemittanceRepository,
) {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("openremit.fx.base-url") { "http://127.0.0.1:1" }
            // kafka.bootstrap-servers는 TestcontainersConfig가 System property로 주입.
            com.openremit.api.TestcontainersConfig.kafka
        }
    }

    private var remittanceId: Long = 0

    @BeforeTest
    fun setup() {
        val user = userRepository.saveAndFlush(
            User(email = "result@example.com", passwordHash = "hash", name = "Result")
        )
        val wallet = Wallet(userId = user.id, currency = Currency.KRW)
        wallet.deposit(Money.of("1000000", Currency.KRW))
        walletRepository.saveAndFlush(wallet)

        // PAID 상태로 미리 저장 (실제 흐름: remittance-api가 markPaid 한 후)
        val remittance = Remittance(
            userId = user.id,
            fromCurrency = Currency.KRW,
            fromAmount = BigDecimal("100000.0000"),
            toCurrency = Currency.USD,
            toAmount = BigDecimal("73.5000"),
            fxRate = BigDecimal("0.000735"),
            receiverName = "John Doe",
            receiverAccount = "1234-5678",
        )
        remittance.markPaid(paymentId = 1L)
        remittanceId = remittanceRepository.saveAndFlush(remittance).id
    }

    @AfterTest
    fun cleanup() {
        remittanceRepository.deleteAllInBatch()
        walletRepository.deleteAllInBatch()
        userRepository.deleteAllInBatch()
    }

    @Test
    fun `payout completed event drives remittance to COMPLETED`() {
        val event = RemittancePayoutCompletedEvent(
            remittanceId = remittanceId,
            payoutTxId = "PAYOUT-ABC",
            occurredAt = Instant.now(),
        )
        kafkaTemplate.send(
            RemittanceEventTopics.PAYOUT_COMPLETED,
            remittanceId.toString(),
            objectMapper.writeValueAsString(event),
        ).get()

        waitForCondition(timeoutMs = 10_000) {
            remittanceRepository.findById(remittanceId).orElse(null)?.status == RemittanceStatus.COMPLETED
        }

        val saved = remittanceRepository.findById(remittanceId).orElseThrow()
        assertEquals(RemittanceStatus.COMPLETED, saved.status)
        assertEquals("PAYOUT-ABC", saved.payoutTxId)
    }

    @Test
    fun `payout failed event drives remittance to FAILED`() {
        val event = RemittancePayoutFailedEvent(
            remittanceId = remittanceId,
            reason = "upstream timeout",
            occurredAt = Instant.now(),
        )
        kafkaTemplate.send(
            RemittanceEventTopics.PAYOUT_FAILED,
            remittanceId.toString(),
            objectMapper.writeValueAsString(event),
        ).get()

        waitForCondition(timeoutMs = 10_000) {
            remittanceRepository.findById(remittanceId).orElse(null)?.status == RemittanceStatus.FAILED
        }

        val saved = remittanceRepository.findById(remittanceId).orElseThrow()
        assertEquals(RemittanceStatus.FAILED, saved.status)
        assertNotNull(saved.failureReason)
    }

    private fun waitForCondition(timeoutMs: Long, intervalMs: Long = 200, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        throw AssertionError("condition not satisfied within $timeoutMs ms")
    }
}
