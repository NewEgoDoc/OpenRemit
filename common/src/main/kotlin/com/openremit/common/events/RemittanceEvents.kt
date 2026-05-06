package com.openremit.common.events

import com.openremit.common.Currency
import java.math.BigDecimal
import java.time.Instant

/**
 * 토픽 `remittance.paid` payload.
 * remittance-api → Debezium → Kafka → payout-worker 소비.
 */
data class RemittancePaidEvent(
    val remittanceId: Long,
    val userId: Long,
    val fromCurrency: Currency,
    val fromAmount: BigDecimal,
    val toCurrency: Currency,
    val toAmount: BigDecimal,
    val receiverName: String,
    val receiverAccount: String,
    val paymentId: Long,
    val occurredAt: Instant,
)

/**
 * 토픽 `remittance.payout.completed` payload.
 * payout-worker → Debezium → Kafka → remittance-api 결과 컨슈머 소비.
 */
data class RemittancePayoutCompletedEvent(
    val remittanceId: Long,
    val payoutTxId: String,
    val occurredAt: Instant,
)

/**
 * 토픽 `remittance.payout.failed` payload.
 */
data class RemittancePayoutFailedEvent(
    val remittanceId: Long,
    val reason: String,
    val occurredAt: Instant,
)

object RemittanceEventTopics {
    const val PAID = "remittance.paid"
    const val PAYOUT_COMPLETED = "remittance.payout.completed"
    const val PAYOUT_FAILED = "remittance.payout.failed"
}
