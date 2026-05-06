package com.openremit.payout.infrastructure.persistence

import com.openremit.payout.domain.PayoutOutboxEvent
import org.springframework.data.jpa.repository.JpaRepository

interface PayoutOutboxRepository : JpaRepository<PayoutOutboxEvent, Long> {
    fun findByAggregateTypeAndAggregateIdOrderByIdAsc(
        aggregateType: String,
        aggregateId: String,
    ): List<PayoutOutboxEvent>
}
