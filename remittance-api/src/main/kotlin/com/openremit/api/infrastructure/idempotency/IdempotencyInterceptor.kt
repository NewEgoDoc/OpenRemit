package com.openremit.api.infrastructure.idempotency

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.util.ContentCachingResponseWrapper
import org.springframework.web.util.WebUtils
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

@Component
class IdempotencyInterceptor(
    private val repository: IdempotencyKeyRepository,
) : HandlerInterceptor {

    private val pathMatcher = AntPathMatcher()

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (!isIdempotentRequest(request)) return true

        val key = request.getHeader(HEADER)?.takeIf { it.isNotBlank() }
            ?: throw MissingIdempotencyKeyException()
        if (key.length > MAX_KEY_LENGTH) {
            throw IdempotencyKeyTooLongException(key.length, MAX_KEY_LENGTH)
        }

        val cachedReq = WebUtils.getNativeRequest(request, CachedBodyHttpServletRequest::class.java)
            ?: throw IllegalStateException("CachedBodyHttpServletRequest not configured for this path")
        val bodyHash = sha256(cachedReq.cachedBody)
        val userId = currentUserId() ?: throw IllegalStateException("Authenticated user required")
        val endpoint = request.requestURI

        val existing = repository.findById(key).orElse(null)
        if (existing != null) {
            if (existing.expiresAt.isBefore(Instant.now())) {
                repository.deleteById(key)
            } else {
                return handleExisting(existing, key, userId, endpoint, bodyHash, response)
            }
        }

        // Reserve the key atomically (placeholder with httpStatus=0 sentinel).
        // If another request races and inserts first, DataIntegrityViolationException is thrown
        // and we re-evaluate using the now-visible row.
        try {
            repository.saveAndFlush(
                IdempotencyKey(
                    key = key,
                    userId = userId,
                    endpoint = endpoint,
                    requestHash = bodyHash,
                    responseBody = "",
                    httpStatus = PENDING_STATUS,
                    expiresAt = Instant.now().plus(TTL),
                )
            )
        } catch (ex: DataIntegrityViolationException) {
            val concurrent = repository.findById(key).orElseThrow { ex }
            return handleExisting(concurrent, key, userId, endpoint, bodyHash, response)
        }

        request.setAttribute(KEY_ATTR, key)
        return true
    }

    private fun handleExisting(
        existing: IdempotencyKey,
        key: String,
        userId: Long,
        endpoint: String,
        bodyHash: String,
        response: HttpServletResponse,
    ): Boolean {
        if (existing.userId != userId || existing.endpoint != endpoint) {
            throw IdempotencyConflictException(
                "Idempotency-Key '$key' is bound to a different user or endpoint"
            )
        }
        if (existing.requestHash != bodyHash) {
            throw IdempotencyConflictException(
                "Idempotency-Key '$key' was used with a different payload"
            )
        }
        if (existing.httpStatus == PENDING_STATUS) {
            throw IdempotencyInProgressException(key)
        }
        replayCachedResponse(response, existing)
        return false
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        val key = request.getAttribute(KEY_ATTR) as? String ?: return
        val placeholder = repository.findById(key).orElse(null) ?: return

        if (ex != null || response.status !in 200..299) {
            // Roll back the reservation so client can retry without conflict
            repository.deleteById(key)
            return
        }

        val cachedResp = WebUtils.getNativeResponse(response, ContentCachingResponseWrapper::class.java) ?: return
        placeholder.responseBody = String(cachedResp.contentAsByteArray, Charsets.UTF_8)
        placeholder.httpStatus = response.status
        repository.save(placeholder)
    }

    private fun isIdempotentRequest(request: HttpServletRequest): Boolean {
        if (request.method != "POST") return false
        return IdempotencyFilter.IDEMPOTENT_PATHS.any { pathMatcher.match(it, request.requestURI) }
    }

    private fun replayCachedResponse(response: HttpServletResponse, cached: IdempotencyKey) {
        response.status = cached.httpStatus
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(cached.responseBody)
        response.writer.flush()
    }

    private fun currentUserId(): Long? {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        return (principal as? Jwt)?.subject?.toLongOrNull()
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val HEADER = "Idempotency-Key"
        const val MAX_KEY_LENGTH = 100
        private const val PENDING_STATUS = 0
        private const val KEY_ATTR = "openremit.idempotency.key"
        private val TTL: Duration = Duration.ofHours(24)
    }
}
