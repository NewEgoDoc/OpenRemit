package com.openremit.webhook.infrastructure.kafka

import com.openremit.common.events.RemittanceEventTopics
import com.openremit.webhook.application.WebhookEnqueueService
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

/**
 * payout-worker가 발행한 결과 이벤트를 받아 webhook 발송 큐에 적재.
 * 같은 토픽을 remittance-api도 별도 consumer-group(`remittance-api-payout-result`)으로 소비 중.
 * webhook-dispatcher는 `webhook-dispatcher` consumer-group으로 별개 오프셋.
 */
@Component
class RemittancePayoutResultConsumer(
    private val webhookEnqueueService: WebhookEnqueueService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [
            RemittanceEventTopics.PAYOUT_COMPLETED,
            RemittanceEventTopics.PAYOUT_FAILED,
        ],
        groupId = "webhook-dispatcher",
    )
    fun consume(
        payload: String,
        @Header(KafkaHeaders.RECEIVED_TOPIC) topic: String,
        @Header(KafkaHeaders.RECEIVED_KEY, required = false) key: String?,
    ) {
        log.debug("received topic={} key={}: {}", topic, key, payload)
        // 멱등성 키: remittanceId(=Kafka key) + topic. payout-worker가 outbox 발행 시 aggregate_id를 키로 넣음.
        // key가 없는 경우는 운영상 발생하지 않지만 안전장치로 payload 해시 대신 명시 skip.
        if (key.isNullOrBlank()) {
            log.warn("dropped payout result without key: topic={}", topic)
            return
        }
        webhookEnqueueService.enqueue(
            eventId = "$key:$topic",
            eventType = topic,
            payload = payload,
        )
    }
}
