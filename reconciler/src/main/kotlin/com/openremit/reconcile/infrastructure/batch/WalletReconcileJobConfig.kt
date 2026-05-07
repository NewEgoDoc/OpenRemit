package com.openremit.reconcile.infrastructure.batch

import com.openremit.reconcile.application.WalletReconcileService
import com.openremit.reconcile.domain.Reconciliation
import com.openremit.reconcile.infrastructure.persistence.ReconciliationRepository
import com.openremit.reconcile.infrastructure.persistence.WalletReconcileQuery
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneId

/**
 * 정산 배치 Job 구성.
 *
 * - 단일 Tasklet Step. wallet 수가 많지 않은 시연 환경에서는 chunk 가 불필요한 오버헤드.
 * - mismatch 발견되어도 Step 은 SUCCESS 로 종료한다 (alert 트리거는 mismatch_count > 0 으로 판단).
 *   Step 을 FAILED 로 만들면 Spring Batch 메타데이터 측면에서 "잡 자체의 결함" 과 구분이 안 된다.
 */
@Configuration
class WalletReconcileJobConfig {

    @Bean
    fun walletReconcileJob(
        jobRepository: JobRepository,
        walletReconcileStep: Step,
    ): Job =
        JobBuilder("walletReconcileJob", jobRepository)
            .start(walletReconcileStep)
            .build()

    @Bean
    fun walletReconcileStep(
        jobRepository: JobRepository,
        transactionManager: PlatformTransactionManager,
        query: WalletReconcileQuery,
        service: WalletReconcileService,
        reconciliationRepository: ReconciliationRepository,
        objectMapper: ObjectMapper,
    ): Step {
        val log = LoggerFactory.getLogger("walletReconcileStep")
        return StepBuilder("walletReconcileStep", jobRepository)
            .tasklet({ _, _ ->
                val snapshots = query.loadSnapshots()
                val result = service.reconcile(snapshots)
                val details = objectMapper.writeValueAsString(result.mismatches)
                reconciliationRepository.save(
                    Reconciliation(
                        targetDate = LocalDate.now(ZoneId.systemDefault()),
                        totalCount = result.totalCount,
                        mismatchCount = result.mismatchCount,
                        details = details,
                    )
                )
                if (result.mismatchCount > 0) {
                    log.warn(
                        "wallet reconciliation found {} mismatches out of {}: {}",
                        result.mismatchCount, result.totalCount, details,
                    )
                } else {
                    log.info("wallet reconciliation OK ({} wallets)", result.totalCount)
                }
                RepeatStatus.FINISHED
            }, transactionManager)
            .build()
    }
}
