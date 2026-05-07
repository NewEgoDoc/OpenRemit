package com.openremit.reconcile.infrastructure.batch

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 매일 정해진 시각에 정산 잡을 실행한다.
 * 같은 잡을 같은 파라미터로는 Spring Batch 가 재실행을 막으므로 timestamp 를 파라미터로 넣어 매 실행을 구분한다.
 */
@Component
class ReconcileScheduler(
    private val jobOperator: JobOperator,
    @Qualifier("walletReconcileJob") private val walletReconcileJob: Job,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${openremit.reconcile.cron:0 0 4 * * *}")
    fun run() {
        val params = JobParametersBuilder()
            .addLong("runAt", System.currentTimeMillis())
            .toJobParameters()
        log.info("triggering walletReconcileJob")
        jobOperator.start(walletReconcileJob, params)
    }
}
