package com.openremit.payout.infrastructure.kafka

import com.openremit.common.events.RemittanceEventTopics
import com.openremit.common.events.RemittancePaidEvent
import com.openremit.payout.application.PayoutProcessor
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class RemittancePaidConsumer(
    private val payoutProcessor: PayoutProcessor,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [RemittanceEventTopics.PAID],
        groupId = "payout-worker",
    )
    fun consume(payload: String) {
        log.debug("received remittance.paid: {}", payload)
        val event = objectMapper.readValue(payload, RemittancePaidEvent::class.java)
        payoutProcessor.process(event)
    }
}
