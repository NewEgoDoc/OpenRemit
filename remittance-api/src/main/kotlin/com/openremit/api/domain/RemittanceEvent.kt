package com.openremit.api.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

@Entity
@Table(name = "remittance_events")
class RemittanceEvent(
    @Column(name = "aggregate_id", nullable = false, length = 64)
    val aggregateId: String,

    @Column(name = "aggregate_type", nullable = false, length = 64)
    val aggregateType: String,

    @Column(name = "event_type", nullable = false, length = 64)
    val eventType: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "json")
    val payload: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    companion object {
        const val AGGREGATE_TYPE_REMITTANCE = "Remittance"
    }
}
