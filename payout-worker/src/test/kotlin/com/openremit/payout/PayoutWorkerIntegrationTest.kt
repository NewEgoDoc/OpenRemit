package com.openremit.payout

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.openremit.common.Currency
import com.openremit.common.events.RemittanceEventTopics
import com.openremit.common.events.RemittancePaidEvent
import com.openremit.payout.domain.PayoutAttemptStatus
import com.openremit.payout.domain.PayoutOutboxEvent
import com.openremit.payout.infrastructure.persistence.PayoutAttemptRepository
import com.openremit.payout.infrastructure.persistence.PayoutOutboxRepository
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
import kotlin.test.assertTrue

@SpringBootTest
@Import(PayoutWorkerTestcontainersConfig::class)
class PayoutWorkerIntegrationTest @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val payoutAttemptRepository: PayoutAttemptRepository,
    private val payoutOutboxRepository: PayoutOutboxRepository,
    private val objectMapper: ObjectMapper,
) {

    companion object {
        private val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            if (!wireMock.isRunning) wireMock.start()
            registry.add("openremit.payout.base-url") { "http://localhost:${wireMock.port()}" }
            // kafka.bootstrap-servers는 PayoutWorkerTestcontainersConfig가 System property로 주입.
            // TestcontainersConfig 클래스 로드를 강제하기 위해 한 번 참조한다.
            PayoutWorkerTestcontainersConfig.kafka
        }
    }

    @BeforeTest
    fun setup() {
        if (!wireMock.isRunning) wireMock.start()
        wireMock.resetAll()

        // Kafka consumer가 비동기로 in-flight 메시지를 처리하므로 stub은 remittanceId별로 분기.
        // priority로 명시적 매칭(99→502)을 우선시키고, 미매칭은 default(200)로 응답.
        wireMock.stubFor(
            post(urlPathEqualTo("/payouts"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.remittance_id", equalTo("99")))
                .willReturn(aResponse().withStatus(502).withBody("upstream down"))
        )
        wireMock.stubFor(
            post(urlPathEqualTo("/payouts"))
                .atPriority(10)
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"tx_id":"PAYOUT-XYZ","status":"COMPLETED"}""")
                )
        )
    }

    @AfterTest
    fun cleanup() {
        payoutOutboxRepository.deleteAllInBatch()
        payoutAttemptRepository.deleteAllInBatch()
    }

    @Test
    fun `consumes remittance paid then calls payout API and writes completed outbox`() {
        val event = paidEvent(remittanceId = 42L)
        kafkaTemplate.send(
            RemittanceEventTopics.PAID,
            event.remittanceId.toString(),
            objectMapper.writeValueAsString(event),
        ).get()

        waitForCondition(timeoutMs = 30_000) {
            payoutAttemptRepository.findByRemittanceId(42L)?.status == PayoutAttemptStatus.COMPLETED
        }

        val attempt = payoutAttemptRepository.findByRemittanceId(42L)
        assertNotNull(attempt)
        assertEquals(PayoutAttemptStatus.COMPLETED, attempt.status)
        assertEquals("PAYOUT-XYZ", attempt.payoutTxId)

        val events = payoutOutboxRepository.findByAggregateTypeAndAggregateIdOrderByIdAsc(
            PayoutOutboxEvent.AGGREGATE_TYPE_REMITTANCE,
            "42",
        )
        assertEquals(1, events.size)
        assertEquals(RemittanceEventTopics.PAYOUT_COMPLETED, events[0].eventType)
        assertTrue(events[0].payload.contains("PAYOUT-XYZ"))
    }

    @Test
    fun `payout 5xx writes failed outbox and marks attempt failed`() {
        val event = paidEvent(remittanceId = 99L)
        kafkaTemplate.send(
            RemittanceEventTopics.PAID,
            event.remittanceId.toString(),
            objectMapper.writeValueAsString(event),
        ).get()

        waitForCondition(timeoutMs = 30_000) {
            payoutAttemptRepository.findByRemittanceId(99L)?.status == PayoutAttemptStatus.FAILED
        }

        val attempt = payoutAttemptRepository.findByRemittanceId(99L)
        assertNotNull(attempt)
        assertEquals(PayoutAttemptStatus.FAILED, attempt.status)

        val events = payoutOutboxRepository.findByAggregateTypeAndAggregateIdOrderByIdAsc(
            PayoutOutboxEvent.AGGREGATE_TYPE_REMITTANCE,
            "99",
        )
        assertEquals(1, events.size)
        assertEquals(RemittanceEventTopics.PAYOUT_FAILED, events[0].eventType)
    }

    private fun paidEvent(remittanceId: Long) = RemittancePaidEvent(
        remittanceId = remittanceId,
        userId = 1L,
        fromCurrency = Currency.KRW,
        fromAmount = BigDecimal("100000.0000"),
        toCurrency = Currency.USD,
        toAmount = BigDecimal("73.5000"),
        receiverName = "John Doe",
        receiverAccount = "1234-5678",
        paymentId = 1L,
        occurredAt = Instant.now(),
    )

    private fun waitForCondition(timeoutMs: Long, intervalMs: Long = 200, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(intervalMs)
        }
        throw AssertionError("condition not satisfied within $timeoutMs ms")
    }
}
