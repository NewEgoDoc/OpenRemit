package com.openremit.webhook.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "webhooks")
class Webhook(
    @Column(name = "event_id", nullable = false, unique = true, length = 100)
    val eventId: String,

    @Column(name = "event_type", nullable = false, length = 64)
    val eventType: String,

    @Column(name = "target_url", nullable = false, length = 500)
    val targetUrl: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    val payload: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: WebhookStatus = WebhookStatus.PENDING

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0

    @Column(name = "next_retry_at")
    var nextRetryAt: Instant? = Instant.now()

    @Column(name = "last_response_status")
    var lastResponseStatus: Int? = null

    @Column(name = "last_failure_reason", length = 500)
    var lastFailureReason: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    /** 발송 성공 — terminal 상태로 전환. */
    fun markSuccess(httpStatus: Int) {
        attemptCount += 1
        status = WebhookStatus.SUCCESS
        lastResponseStatus = httpStatus
        lastFailureReason = null
        nextRetryAt = null
        updatedAt = Instant.now()
    }

    /**
     * 발송 실패. attempt_count를 증가시키고:
     *   - 남은 시도가 있으면 status=PENDING, next_retry_at = now + delay
     *   - 마지막 시도였으면 status=FAILED, next_retry_at=null (terminal)
     *
     * @param maxAttempts 최대 시도 횟수 (이 값에 도달하면 terminal)
     * @param nextDelay 다음 시도까지 대기 시간 (마지막 시도면 무시)
     */
    fun markFailed(httpStatus: Int?, reason: String, maxAttempts: Int, nextDelay: java.time.Duration) {
        attemptCount += 1
        lastResponseStatus = httpStatus
        lastFailureReason = reason.take(500)
        if (attemptCount >= maxAttempts) {
            status = WebhookStatus.FAILED
            nextRetryAt = null
        } else {
            status = WebhookStatus.PENDING
            nextRetryAt = Instant.now().plus(nextDelay)
        }
        updatedAt = Instant.now()
    }
}

enum class WebhookStatus {
    PENDING,
    SUCCESS,
    FAILED,
}
