package com.openremit.api.infrastructure.security

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JwtTokenProvider(
    private val encoder: JwtEncoder,
    private val properties: JwtProperties,
) {
    fun issue(userId: Long, email: String): IssuedToken {
        val now = Instant.now()
        val expiresAt = now.plus(properties.ttl)
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .subject(userId.toString())
            .claim("email", email)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedToken(token, expiresAt)
    }

    data class IssuedToken(val token: String, val expiresAt: Instant)
}
