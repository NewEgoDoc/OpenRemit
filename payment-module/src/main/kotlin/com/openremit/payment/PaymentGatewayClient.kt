package com.openremit.payment

import com.openremit.common.Money

interface PaymentGatewayClient {
    fun approve(command: ApproveCommand): ApproveResult

    data class ApproveCommand(
        val userId: Long,
        val amount: Money,
        val method: PaymentMethod,
    )

    data class ApproveResult(
        val paymentId: Long,
        val externalTxId: String,
    )
}
