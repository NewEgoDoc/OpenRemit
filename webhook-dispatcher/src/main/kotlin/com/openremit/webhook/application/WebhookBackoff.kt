package com.openremit.webhook.application

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import kotlin.math.pow

/**
 * Exponential backoff. attempt(1-base)에 대한 다음 대기 시간 계산.
 *   attempt=1 → base
 *   attempt=2 → base * multiplier
 *   attempt=N → base * multiplier^(N-1)
 *
 * 데모 기본값: 1s, 2s, 4s, 8s, 16s (총 5회 시도, 31초).
 */
@ConfigurationProperties(prefix = "openremit.webhook.backoff")
data class WebhookBackoffProperties(
    val baseMillis: Long = 1000,
    val multiplier: Double = 2.0,
    val maxAttempts: Int = 5,
) {
    fun delayFor(attempt: Int): Duration {
        require(attempt >= 1) { "attempt must be >= 1, was $attempt" }
        val millis = baseMillis.toDouble() * multiplier.pow(attempt - 1)
        return Duration.ofMillis(millis.toLong())
    }
}
