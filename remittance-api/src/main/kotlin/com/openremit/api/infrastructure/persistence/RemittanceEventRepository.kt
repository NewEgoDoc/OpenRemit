package com.openremit.api.infrastructure.persistence

import com.openremit.api.domain.RemittanceEvent
import org.springframework.data.jpa.repository.JpaRepository

interface RemittanceEventRepository : JpaRepository<RemittanceEvent, Long> {
    fun findByAggregateTypeAndAggregateIdOrderByIdAsc(
        aggregateType: String,
        aggregateId: String,
    ): List<RemittanceEvent>
}
