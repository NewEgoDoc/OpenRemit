package com.openremit.payout.application

import com.openremit.common.events.RemittanceEventTopics
import com.openremit.common.events.RemittancePaidEvent
import com.openremit.common.events.RemittancePayoutCompletedEvent
import com.openremit.common.events.RemittancePayoutFailedEvent
import com.openremit.payout.domain.PayoutAttempt
import com.openremit.payout.domain.PayoutOutboxEvent
import com.openremit.payout.infrastructure.client.PayoutClient
import com.openremit.payout.infrastructure.persistence.PayoutAttemptRepository
import com.openremit.payout.infrastructure.persistence.PayoutOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Instant

@Service
class PayoutProcessor(
    private val payoutAttemptRepository: PayoutAttemptRepository,
    private val payoutOutboxRepository: PayoutOutboxRepository,
    private val payoutClient: PayoutClient,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 단일 트랜잭션으로 처리:
     *   1) PayoutAttempt 사전 SELECT(멱등성 체크) → 없으면 INSERT
     *   2) 송금사 API 호출 (외부 I/O — 트랜잭션 안이지만 단일 호출이라 짧음)
     *   3) attempts 상태 업데이트 + payout_outbox INSERT (Debezium 발행 대상)
     *
     * 멱등성: 사전 SELECT로 차단. UNIQUE 제약 위반을 catch하던 이전 패턴은 트랜잭션을
     *   rollback-only로 마킹해 commit 시 UnexpectedRollbackException → Kafka partition stuck.
     *   Kafka 키(remittanceId) 기반 파티셔닝으로 동일 키가 단일 컨슈머 스레드에 직렬화되므로
     *   사전 체크의 TOCTOU race는 실무상 발생하지 않는다.
     *
     * 트레이드오프: 외부 I/O가 트랜잭션에 포함되어 DB 커넥션을 점유. 송금사 호출이 짧을 때만 안전.
     * 호출이 길어지면 향후 (1) attempts INSERT만 트랜잭션 1, (2) 송금사 호출, (3) outbox INSERT를
     * 트랜잭션 2로 분리 + reconciler가 PENDING attempts 회수하는 구조로 진화.
     */
    @Transactional
    fun process(event: RemittancePaidEvent) {
        if (payoutAttemptRepository.findByRemittanceId(event.remittanceId) != null) {
            log.info("payout attempt for remittanceId={} already exists — skipping", event.remittanceId)
            return
        }
        val attempt = payoutAttemptRepository.saveAndFlush(PayoutAttempt(remittanceId = event.remittanceId))

        val outbox: PayoutOutboxEvent = try {
            val result = payoutClient.payout(
                PayoutClient.PayoutCommand(
                    remittanceId = event.remittanceId,
                    toCurrency = event.toCurrency.name,
                    toAmount = event.toAmount,
                    receiverName = event.receiverName,
                    receiverAccount = event.receiverAccount,
                )
            )
            attempt.markCompleted(result.txId)
            buildOutbox(
                remittanceId = event.remittanceId,
                eventType = RemittanceEventTopics.PAYOUT_COMPLETED,
                payload = RemittancePayoutCompletedEvent(
                    remittanceId = event.remittanceId,
                    payoutTxId = result.txId,
                    occurredAt = Instant.now(),
                ),
            )
        } catch (e: Exception) {
            log.warn("payout call failed for remittanceId={}: {}", event.remittanceId, e.message)
            val reason = (e.message ?: e.javaClass.simpleName).take(500)
            attempt.markFailed(reason)
            buildOutbox(
                remittanceId = event.remittanceId,
                eventType = RemittanceEventTopics.PAYOUT_FAILED,
                payload = RemittancePayoutFailedEvent(
                    remittanceId = event.remittanceId,
                    reason = reason,
                    occurredAt = Instant.now(),
                ),
            )
        }

        payoutOutboxRepository.save(outbox)
    }

    private fun buildOutbox(remittanceId: Long, eventType: String, payload: Any): PayoutOutboxEvent =
        PayoutOutboxEvent(
            aggregateId = remittanceId.toString(),
            aggregateType = PayoutOutboxEvent.AGGREGATE_TYPE_REMITTANCE,
            eventType = eventType,
            payload = objectMapper.writeValueAsString(payload),
        )
}
