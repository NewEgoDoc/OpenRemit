package com.openremit.api.interfaces.remittance

import com.openremit.api.domain.Remittance
import com.openremit.api.domain.RemittanceStatus
import com.openremit.common.Currency
import com.openremit.payment.PaymentMethod
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant

data class RemittanceCreateRequest(
    @field:NotNull val fromCurrency: Currency,
    @field:NotNull
    @field:DecimalMin(value = "0", inclusive = false)
    val fromAmount: BigDecimal,
    @field:NotNull val toCurrency: Currency,
    @field:NotBlank @field:Size(max = 100) val receiverName: String,
    @field:NotBlank @field:Size(max = 100) val receiverAccount: String,
    @field:NotNull val method: PaymentMethod,
)

data class RemittanceResponse(
    val id: Long,
    val status: RemittanceStatus,
    val fromCurrency: Currency,
    val fromAmount: BigDecimal,
    val toCurrency: Currency,
    val toAmount: BigDecimal,
    val fxRate: BigDecimal,
    val receiverName: String,
    val receiverAccount: String,
    val paymentId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(r: Remittance) = RemittanceResponse(
            id = r.id,
            status = r.status,
            fromCurrency = r.fromCurrency,
            fromAmount = r.fromAmount,
            toCurrency = r.toCurrency,
            toAmount = r.toAmount,
            fxRate = r.fxRate,
            receiverName = r.receiverName,
            receiverAccount = r.receiverAccount,
            paymentId = r.paymentId,
            createdAt = r.createdAt,
            updatedAt = r.updatedAt,
        )
    }
}
