package com.openremit.payout.infrastructure.persistence

import com.openremit.payout.domain.PayoutAttempt
import org.springframework.data.jpa.repository.JpaRepository

interface PayoutAttemptRepository : JpaRepository<PayoutAttempt, Long> {
    fun findByRemittanceId(remittanceId: Long): PayoutAttempt?
}
