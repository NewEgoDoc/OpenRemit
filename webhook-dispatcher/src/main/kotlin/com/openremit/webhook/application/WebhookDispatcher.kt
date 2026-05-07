package com.openremit.webhook.application

import com.openremit.webhook.domain.WebhookStatus
import com.openremit.webhook.infrastructure.persistence.WebhookRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 폴링 스케줄러. PENDING + next_retry_at <= now 인 webhook 행을 회수해
 * [WebhookSender]로 한 행씩 발송 시도.
 *
 * 트레이드오프: SELECT 후 row-level 락 없음 → 단일 인스턴스 가정.
 *   다중 인스턴스 운영 시 SELECT ... FOR UPDATE SKIP LOCKED 필요. 현재는 데모 범위 밖.
 */
@Service
class WebhookDispatcher(
    private val webhookRepository: WebhookRepository,
    private val webhookSender: WebhookSender,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${openremit.webhook.poll-interval-millis:1000}")
    fun dispatchDue() {
        val due = webhookRepository.findDueForRetry(
            status = WebhookStatus.PENDING,
            now = Instant.now(),
            pageable = PageRequest.of(0, BATCH_SIZE),
        )
        if (due.isEmpty()) return
        log.debug("dispatching {} due webhooks", due.size)
        for (webhook in due) {
            webhookSender.attemptSend(webhook.id)
        }
    }

    companion object {
        private const val BATCH_SIZE = 50
    }
}
