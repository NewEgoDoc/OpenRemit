package com.openremit.api.infrastructure.persistence

import com.openremit.api.domain.WalletTransaction
import org.springframework.data.jpa.repository.JpaRepository

interface WalletTransactionRepository : JpaRepository<WalletTransaction, Long> {
    fun findByWalletIdOrderByIdAsc(walletId: Long): List<WalletTransaction>
}
