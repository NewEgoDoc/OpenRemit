package com.openremit.api.interfaces.remittance

import com.openremit.api.application.remittance.RemittanceCreateUseCase
import com.openremit.common.ReceiverInfo
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/remittances")
class RemittanceController(
    private val createUseCase: RemittanceCreateUseCase,
) {
    @PostMapping
    fun create(
        @Valid @RequestBody request: RemittanceCreateRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<RemittanceResponse> {
        val remittance = createUseCase.create(
            RemittanceCreateUseCase.CreateCommand(
                userId = jwt.subject.toLong(),
                fromCurrency = request.fromCurrency,
                fromAmount = request.fromAmount,
                toCurrency = request.toCurrency,
                receiver = ReceiverInfo(name = request.receiverName, account = request.receiverAccount),
                paymentMethod = request.method,
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(RemittanceResponse.from(remittance))
    }
}
