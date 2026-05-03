package com.openremit.api.infrastructure.persistence

import com.openremit.api.domain.Wallet
import org.springframework.data.jpa.repository.JpaRepository

interface WalletRepository : JpaRepository<Wallet, Long> {
    fun findByUserId(userId: Long): Wallet?
}
