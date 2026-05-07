package com.openremit.reconcile.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

@Entity
@Table(name = "reconciliations")
class Reconciliation(
    @Column(name = "target_date", nullable = false)
    val targetDate: LocalDate,

    @Column(name = "total_count", nullable = false)
    val totalCount: Int,

    @Column(name = "mismatch_count", nullable = false)
    val mismatchCount: Int,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "json")
    val details: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
}
