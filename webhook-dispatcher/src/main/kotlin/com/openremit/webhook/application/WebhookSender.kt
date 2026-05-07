package com.openremit.webhook.application

import com.openremit.webhook.domain.Webhook
import com.openremit.webhook.domain.WebhookStatus
import com.openremit.webhook.infrastructure.client.WebhookClient
import com.openremit.webhook.infrastructure.client.WebhookSendException
import com.openremit.webhook.infrastructure.persistence.WebhookRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 단일 webhook 행에 대한 발송 시도. 트랜잭션을 행 단위로 분리해 한 행 실패가
 * 같은 배치의 다른 행 처리를 방해하지 않도록 한다.
 *
 * 별도 빈으로 분리한 이유: WebhookDispatcher의 스케줄러 메서드가 호출하면
 * 같은 클래스 self-invocation이라 @Transactional 프록시가 적용되지 않는다.
 */
@Service
class WebhookSender(
    private val webhookRepository: WebhookRepository,
    private val webhookClient: WebhookClient,
    private val backoff: WebhookBackoffProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun attemptSend(webhookId: Long) {
        val webhook = webhookRepository.findById(webhookId).orElse(null) ?: return
        if (webhook.status != WebhookStatus.PENDING) return

        try {
            val result = webhookClient.send(webhook.targetUrl, webhook.payload)
            webhook.markSuccess(result.httpStatus)
            log.info(
                "webhook id={} eventId={} sent successfully (httpStatus={}, attempt={})",
                webhook.id, webhook.eventId, result.httpStatus, webhook.attemptCount,
            )
        } catch (e: WebhookSendException) {
            handleFailure(webhook, e)
        }
    }

    private fun handleFailure(webhook: Webhook, e: WebhookSendException) {
        val nextAttempt = webhook.attemptCount + 1
        val nextDelay = backoff.delayFor(nextAttempt)
        webhook.markFailed(
            httpStatus = e.httpStatus,
            reason = e.message ?: "unknown",
            maxAttempts = backoff.maxAttempts,
            nextDelay = nextDelay,
        )
        if (webhook.status == WebhookStatus.FAILED) {
            log.warn(
                "webhook id={} eventId={} reached max attempts ({}) — terminal FAILED",
                webhook.id, webhook.eventId, backoff.maxAttempts,
            )
        } else {
            log.info(
                "webhook id={} eventId={} attempt {} failed (httpStatus={}); next retry in {}",
                webhook.id, webhook.eventId, webhook.attemptCount, e.httpStatus, nextDelay,
            )
        }
    }
}
