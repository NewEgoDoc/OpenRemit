package com.openremit.api.application.remittance

import com.openremit.api.domain.Remittance
import com.openremit.api.domain.RemittanceEvent
import com.openremit.api.infrastructure.fx.FxRateProvider
import com.openremit.api.infrastructure.lock.WalletLockService
import com.openremit.api.infrastructure.persistence.RemittanceEventRepository
import com.openremit.api.infrastructure.persistence.RemittanceRepository
import com.openremit.api.infrastructure.persistence.WalletRepository
import com.openremit.common.Currency
import com.openremit.common.Money
import com.openremit.common.ReceiverInfo
import com.openremit.common.events.RemittanceEventTopics
import com.openremit.common.events.RemittancePaidEvent
import com.openremit.payment.PaymentGatewayClient
import com.openremit.payment.PaymentMethod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Service
class RemittanceCreateUseCase(
    private val walletLockService: WalletLockService,
    private val remittanceCreator: RemittanceCreator,
) {

    fun create(command: CreateCommand): Remittance =
        walletLockService.withWalletLock(command.userId) {
            remittanceCreator.create(command)
        }

    data class CreateCommand(
        val userId: Long,
        val fromCurrency: Currency,
        val fromAmount: BigDecimal,
        val toCurrency: Currency,
        val receiver: ReceiverInfo,
        val paymentMethod: PaymentMethod,
    )
}

@Service
class RemittanceCreator(
    private val walletRepository: WalletRepository,
    private val remittanceRepository: RemittanceRepository,
    private val remittanceEventRepository: RemittanceEventRepository,
    private val paymentGateway: PaymentGatewayClient,
    private val fxRateProvider: FxRateProvider,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun create(command: RemittanceCreateUseCase.CreateCommand): Remittance {
        val wallet = walletRepository.findByUserId(command.userId)
            ?: throw WalletNotFoundException(command.userId)

        val fromMoney = Money(command.fromAmount, command.fromCurrency)
        wallet.withdraw(fromMoney)

        val payment = paymentGateway.approve(
            PaymentGatewayClient.ApproveCommand(
                userId = command.userId,
                amount = fromMoney,
                method = command.paymentMethod,
            )
        )

        val fxRate = fxRateProvider.rate(command.fromCurrency, command.toCurrency)
        val toAmount = command.fromAmount.multiply(fxRate)
            .setScale(Money.SCALE, RoundingMode.HALF_EVEN)

        val remittance = Remittance(
            userId = command.userId,
            fromCurrency = command.fromCurrency,
            fromAmount = command.fromAmount,
            toCurrency = command.toCurrency,
            toAmount = toAmount,
            fxRate = fxRate,
            receiverName = command.receiver.name,
            receiverAccount = command.receiver.account,
        )
        remittance.markPaid(paymentId = payment.paymentId)

        val saved = remittanceRepository.save(remittance)

        // Outbox INSERT — 같은 트랜잭션. Debezium이 binlog → Kafka(remittance.paid) 발행.
        val payload = RemittancePaidEvent(
            remittanceId = saved.id,
            userId = saved.userId,
            fromCurrency = saved.fromCurrency,
            fromAmount = saved.fromAmount,
            toCurrency = saved.toCurrency,
            toAmount = saved.toAmount,
            receiverName = saved.receiverName,
            receiverAccount = saved.receiverAccount,
            paymentId = payment.paymentId,
            occurredAt = Instant.now(),
        )
        remittanceEventRepository.save(
            RemittanceEvent(
                aggregateId = saved.id.toString(),
                aggregateType = RemittanceEvent.AGGREGATE_TYPE_REMITTANCE,
                eventType = RemittanceEventTopics.PAID,
                payload = objectMapper.writeValueAsString(payload),
            )
        )

        return saved
    }
}
