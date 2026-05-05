package com.openremit.api.infrastructure.idempotency

import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebMvcConfig(
    private val idempotencyInterceptor: IdempotencyInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(idempotencyInterceptor)
            .addPathPatterns(IdempotencyFilter.IDEMPOTENT_PATHS)
    }
}
