package com.openremit.reconcile.infrastructure.persistence

import com.openremit.reconcile.domain.Reconciliation
import org.springframework.data.jpa.repository.JpaRepository

interface ReconciliationRepository : JpaRepository<Reconciliation, Long>
