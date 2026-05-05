package com.openremit.api.infrastructure.idempotency

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "idempotency_keys")
class IdempotencyKey(
    @Id
    @Column(name = "idempotency_key", length = 100)
    val key: String,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, length = 100)
    val endpoint: String,

    @Column(name = "request_hash", nullable = false, length = 64)
    val requestHash: String,

    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "response_body", nullable = false)
    var responseBody: String,

    @Column(name = "http_status", nullable = false)
    var httpStatus: Int,

    @Column(name = "expires_at", nullable = false)
    val expiresAt: Instant,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
