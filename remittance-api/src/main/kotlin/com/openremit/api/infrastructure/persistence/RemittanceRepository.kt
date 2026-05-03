package com.openremit.api.infrastructure.persistence

import com.openremit.api.domain.Remittance
import com.openremit.api.domain.RemittanceStatus
import org.springframework.data.jpa.repository.JpaRepository

interface RemittanceRepository : JpaRepository<Remittance, Long> {
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<Remittance>
    fun findByUserIdAndStatus(userId: Long, status: RemittanceStatus): List<Remittance>
}
