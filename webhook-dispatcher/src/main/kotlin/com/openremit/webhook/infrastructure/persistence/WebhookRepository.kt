package com.openremit.webhook.infrastructure.persistence

import com.openremit.webhook.domain.Webhook
import com.openremit.webhook.domain.WebhookStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface WebhookRepository : JpaRepository<Webhook, Long> {
    fun findByEventId(eventId: String): Webhook?

    /**
     * 폴링 스케줄러가 호출. PENDING + next_retry_at <= now 인 행을
     * id 오름차순으로 한 페이지씩 회수.
     */
    @Query(
        """
        SELECT w FROM Webhook w
        WHERE w.status = :status
          AND w.nextRetryAt IS NOT NULL
          AND w.nextRetryAt <= :now
        ORDER BY w.id ASC
        """
    )
    fun findDueForRetry(
        @Param("status") status: WebhookStatus,
        @Param("now") now: Instant,
        pageable: Pageable,
    ): List<Webhook>
}
