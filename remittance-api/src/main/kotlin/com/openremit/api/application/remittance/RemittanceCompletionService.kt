package com.openremit.api.application.remittance

import com.openremit.api.domain.RemittanceStatus
import com.openremit.api.infrastructure.persistence.RemittanceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * payout-worker 결과(Kafka 토픽 `remittance.payout.completed/failed`)를 받아
 * 송금 상태 머신을 갱신한다.
 *
 * 멱등성: 동일 메시지 재소비 시 이미 terminal 상태면 skip.
 *
 * 상태 전이: PAID → PROCESSING → COMPLETED/FAILED.
 *   PAID에서 도착하면 markProcessing() 한 번 거치고 최종 상태로 이동.
 */
@Service
class RemittanceCompletionService(
    private val remittanceRepository: RemittanceRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun complete(remittanceId: Long, payoutTxId: String) {
        val remittance = remittanceRepository.findById(remittanceId).orElse(null)
        if (remittance == null) {
            log.warn("complete: remittance {} not found — skipping", remittanceId)
            return
        }
        if (remittance.status.isTerminal) {
            log.info("complete: remittance {} already terminal ({}), skipping", remittanceId, remittance.status)
            return
        }
        if (remittance.status == RemittanceStatus.PAID) {
            remittance.markProcessing()
        }
        remittance.markCompleted(payoutTxId)
    }

    @Transactional
    fun fail(remittanceId: Long, reason: String) {
        val remittance = remittanceRepository.findById(remittanceId).orElse(null)
        if (remittance == null) {
            log.warn("fail: remittance {} not found — skipping", remittanceId)
            return
        }
        if (remittance.status.isTerminal) {
            log.info("fail: remittance {} already terminal ({}), skipping", remittanceId, remittance.status)
            return
        }
        if (remittance.status == RemittanceStatus.PAID) {
            remittance.markProcessing()
        }
        remittance.markFailed(reason)
    }
}
