package com.openremit.webhook

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.openremit.common.events.RemittanceEventTopics
import com.openremit.webhook.domain.WebhookStatus
import com.openremit.webhook.infrastructure.persistence.WebhookRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@Import(WebhookDispatcherTestcontainersConfig::class)
class WebhookDispatcherIntegrationTest @Autowired constructor(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val webhookRepository: WebhookRepository,
) {
    companion object {
        private val wireMock: WireMockServer = WireMockServer(wireMockConfig().dynamicPort())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            if (!wireMock.isRunning) wireMock.start()
            registry.add("openremit.webhook.target-url") { "http://localhost:${wireMock.port()}/webhook" }
            // 테스트 시 backoff 단축: 50ms × 2 = 50/100/200/400/800ms (총 5회, ~1.5초)
            registry.add("openremit.webhook.backoff.base-millis") { "50" }
            registry.add("openremit.webhook.backoff.multiplier") { "2.0" }
            registry.add("openremit.webhook.backoff.max-attempts") { "5" }
            registry.add("openremit.webhook.poll-interval-millis") { "100" }
            // TestcontainersConfig 클래스 로드를 강제 (Kafka container 시작).
            WebhookDispatcherTestcontainersConfig.kafka
        }
    }

    @BeforeTest
    fun setup() {
        if (!wireMock.isRunning) wireMock.start()
        wireMock.resetAll()
    }

    @AfterTest
    fun cleanup() {
        webhookRepository.deleteAllInBatch()
    }

    @Test
    fun `payout completed event triggers webhook send and marks SUCCESS`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200))
        )

        val remittanceId = 100L
        val payload = """{"remittance_id":$remittanceId,"payout_tx_id":"PAYOUT-X","occurred_at":"2026-05-09T00:00:00Z"}"""
        kafkaTemplate.send(
            RemittanceEventTopics.PAYOUT_COMPLETED,
            remittanceId.toString(),
            payload,
        ).get()

        val webhook = waitForWebhook(eventId = "$remittanceId:${RemittanceEventTopics.PAYOUT_COMPLETED}", timeoutMs = 30_000) {
            it.status == WebhookStatus.SUCCESS
        }
        assertEquals(WebhookStatus.SUCCESS, webhook.status)
        assertEquals(1, webhook.attemptCount)
        assertEquals(200, webhook.lastResponseStatus)
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/webhook")))
    }

    @Test
    fun `payout failed event hits 502 then retries 5 times before marking FAILED`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(502).withBody("upstream down"))
        )

        val remittanceId = 200L
        val payload = """{"remittance_id":$remittanceId,"reason":"timeout","occurred_at":"2026-05-09T00:00:00Z"}"""
        kafkaTemplate.send(
            RemittanceEventTopics.PAYOUT_FAILED,
            remittanceId.toString(),
            payload,
        ).get()

        val webhook = waitForWebhook(eventId = "$remittanceId:${RemittanceEventTopics.PAYOUT_FAILED}", timeoutMs = 30_000) {
            it.status == WebhookStatus.FAILED
        }
        assertEquals(WebhookStatus.FAILED, webhook.status)
        assertEquals(5, webhook.attemptCount)
        assertEquals(502, webhook.lastResponseStatus)
        wireMock.verify(5, postRequestedFor(urlPathEqualTo("/webhook")))
    }

    @Test
    fun `duplicate payout result with same key is deduplicated by event_id`() {
        wireMock.stubFor(
            post(urlPathEqualTo("/webhook"))
                .willReturn(aResponse().withStatus(200))
        )

        val remittanceId = 300L
        val payload = """{"remittance_id":$remittanceId,"payout_tx_id":"PAYOUT-DUP","occurred_at":"2026-05-09T00:00:00Z"}"""
        repeat(3) {
            kafkaTemplate.send(
                RemittanceEventTopics.PAYOUT_COMPLETED,
                remittanceId.toString(),
                payload,
            ).get()
        }

        val webhook = waitForWebhook(eventId = "$remittanceId:${RemittanceEventTopics.PAYOUT_COMPLETED}", timeoutMs = 30_000) {
            it.status == WebhookStatus.SUCCESS
        }
        assertEquals(1, webhook.attemptCount)
        // 같은 event_id로 INSERT 1회만 성공. Kafka 재소비가 webhook을 중복 발송시키지 않음.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/webhook")))
    }

    private fun waitForWebhook(
        eventId: String,
        timeoutMs: Long,
        intervalMs: Long = 100,
        condition: (com.openremit.webhook.domain.Webhook) -> Boolean,
    ): com.openremit.webhook.domain.Webhook {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val w = webhookRepository.findByEventId(eventId)
            if (w != null && condition(w)) return w
            Thread.sleep(intervalMs)
        }
        val w = webhookRepository.findByEventId(eventId)
        assertNotNull(w, "webhook with eventId=$eventId not found within $timeoutMs ms")
        throw AssertionError("webhook condition not satisfied within $timeoutMs ms; status=${w.status}, attempts=${w.attemptCount}")
    }
}
