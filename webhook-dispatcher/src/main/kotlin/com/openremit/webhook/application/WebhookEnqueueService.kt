package com.openremit.webhook.application

import com.openremit.webhook.domain.Webhook
import com.openremit.webhook.infrastructure.client.WebhookClientProperties
import com.openremit.webhook.infrastructure.persistence.WebhookRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Kafka 컨슈머가 호출. event_id로 멱등성 보장:
 *   동일 (remittanceId, eventType) 메시지 재소비 시 사전 SELECT로 skip.
 *
 * 사전 체크 이유: UNIQUE 제약 위반(`DataIntegrityViolationException`)을 단순 catch하면
 * @Transactional 트랜잭션이 rollback-only로 마킹되어 commit 시 UnexpectedRollbackException이
 * 발생한다. Kafka 컨슈머가 ack 못 하고 같은 메시지를 무한 재배송 → partition stuck.
 *
 * Kafka는 키 기반 파티셔닝으로 동일 event_id가 단일 컨슈머 스레드에 직렬화되므로 사전 체크의
 * TOCTOU race는 실무상 발생하지 않는다. 만에 하나 발생해도 redelivery 시 SELECT가 잡아준다.
 */
@Service
class WebhookEnqueueService(
    private val webhookRepository: WebhookRepository,
    private val clientProperties: WebhookClientProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun enqueue(eventId: String, eventType: String, payload: String) {
        if (webhookRepository.findByEventId(eventId) != null) {
            log.info("webhook for eventId={} already enqueued — skipping", eventId)
            return
        }
        webhookRepository.save(
            Webhook(
                eventId = eventId,
                eventType = eventType,
                targetUrl = clientProperties.targetUrl,
                payload = payload,
            )
        )
    }
}
