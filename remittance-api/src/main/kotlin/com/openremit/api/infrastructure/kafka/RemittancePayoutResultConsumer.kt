package com.openremit.api.infrastructure.kafka

import com.openremit.api.application.remittance.RemittanceCompletionService
import com.openremit.common.events.RemittanceEventTopics
import com.openremit.common.events.RemittancePayoutCompletedEvent
import com.openremit.common.events.RemittancePayoutFailedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class RemittancePayoutResultConsumer(
    private val completionService: RemittanceCompletionService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [RemittanceEventTopics.PAYOUT_COMPLETED],
        groupId = "remittance-api-payout-result",
    )
    fun onCompleted(payload: String) {
        log.debug("received {}: {}", RemittanceEventTopics.PAYOUT_COMPLETED, payload)
        val event = objectMapper.readValue(payload, RemittancePayoutCompletedEvent::class.java)
        completionService.complete(event.remittanceId, event.payoutTxId)
    }

    @KafkaListener(
        topics = [RemittanceEventTopics.PAYOUT_FAILED],
        groupId = "remittance-api-payout-result",
    )
    fun onFailed(payload: String) {
        log.debug("received {}: {}", RemittanceEventTopics.PAYOUT_FAILED, payload)
        val event = objectMapper.readValue(payload, RemittancePayoutFailedEvent::class.java)
        completionService.fail(event.remittanceId, event.reason)
    }
}
