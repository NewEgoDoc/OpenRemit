package com.openremit.api.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "remittance_status_history")
class RemittanceStatusHistory(
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, length = 20)
    val fromStatus: RemittanceStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    val toStatus: RemittanceStatus,

    @Column(name = "reason", length = 500)
    val reason: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0
}
