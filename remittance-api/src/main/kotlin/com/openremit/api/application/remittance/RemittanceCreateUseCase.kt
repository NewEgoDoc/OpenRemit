package com.openremit.api.application.remittance

import com.openremit.api.domain.Remittance
import com.openremit.api.infrastructure.fx.FxRateProvider
import com.openremit.api.infrastructure.lock.WalletLockService
import com.openremit.api.infrastructure.persistence.RemittanceRepository
import com.openremit.api.infrastructure.persistence.WalletRepository
import com.openremit.common.Currency
import com.openremit.common.Money
import com.openremit.common.ReceiverInfo
import com.openremit.payment.PaymentGatewayClient
import com.openremit.payment.PaymentMethod
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

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
    private val paymentGateway: PaymentGatewayClient,
    private val fxRateProvider: FxRateProvider,
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

        return remittanceRepository.save(remittance)
    }
}
