package com.openremit.api.infrastructure.security

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("openremit.jwt")
data class JwtProperties(
    val secret: String,
    val issuer: String = "https://openremit.dev",
    val ttl: Duration = Duration.ofHours(1),
)
