package com.openremit.api.infrastructure.idempotency

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.ContentCachingResponseWrapper

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
class IdempotencyFilter : OncePerRequestFilter() {

    private val pathMatcher = AntPathMatcher()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!shouldWrap(request)) {
            filterChain.doFilter(request, response)
            return
        }
        val wrappedReq = CachedBodyHttpServletRequest(request)
        val wrappedResp = ContentCachingResponseWrapper(response)
        try {
            filterChain.doFilter(wrappedReq, wrappedResp)
        } finally {
            wrappedResp.copyBodyToResponse()
        }
    }

    private fun shouldWrap(request: HttpServletRequest): Boolean {
        if (request.method != "POST") return false
        return IDEMPOTENT_PATHS.any { pathMatcher.match(it, request.requestURI) }
    }

    companion object {
        val IDEMPOTENT_PATHS = listOf(
            "/api/v1/remittances",
            "/api/v1/payments",
        )
    }
}
