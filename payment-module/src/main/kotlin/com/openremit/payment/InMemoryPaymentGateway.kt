package com.openremit.payment

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
class InMemoryPaymentGateway(
    private val paymentRepository: PaymentRepository,
) : PaymentGatewayClient {

    @Transactional
    override fun approve(command: PaymentGatewayClient.ApproveCommand): PaymentGatewayClient.ApproveResult {
        val payment = Payment(
            userId = command.userId,
            amount = command.amount.amount,
            currency = command.amount.currency,
            method = command.method,
        )
        payment.externalTxId = "INMEM-${UUID.randomUUID()}"
        val saved = paymentRepository.save(payment)
        return PaymentGatewayClient.ApproveResult(
            paymentId = saved.id,
            externalTxId = saved.externalTxId!!,
        )
    }
}
