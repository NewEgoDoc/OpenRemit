package com.openremit.payout.infrastructure.client

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.math.BigDecimal

interface PayoutClient {
    /**
     * 송금사 API 호출. 4xx/5xx/IO 예외는 그대로 throw — 호출자가 처리.
     */
    fun payout(command: PayoutCommand): PayoutResult

    data class PayoutCommand(
        val remittanceId: Long,
        val toCurrency: String,
        val toAmount: BigDecimal,
        val receiverName: String,
        val receiverAccount: String,
    )

    data class PayoutResult(
        val txId: String,
        val status: String,
    )
}

@ConfigurationProperties(prefix = "openremit.payout")
data class PayoutClientProperties(
    val baseUrl: String,
)

@Configuration
class PayoutClientConfig {
    @Bean
    fun payoutRestClient(properties: PayoutClientProperties): RestClient =
        // SimpleClientHttpRequestFactory(JDK HttpURLConnection)를 명시 — 기본 RestClient
        // 후보(HttpClient5/JDK HttpClient)가 HTTP/2 negotiate를 시도해 WireMock과 RST_STREAM
        // 호환성 문제를 일으키는 것을 방지.
        RestClient.builder()
            .baseUrl(properties.baseUrl)
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
}

@Component
class RestClientPayoutClient(
    private val payoutRestClient: RestClient,
) : PayoutClient {

    override fun payout(command: PayoutClient.PayoutCommand): PayoutClient.PayoutResult =
        payoutRestClient.post()
            .uri("/payouts")
            .body(
                mapOf(
                    "remittance_id" to command.remittanceId,
                    "to_currency" to command.toCurrency,
                    "to_amount" to command.toAmount,
                    "receiver_name" to command.receiverName,
                    "receiver_account" to command.receiverAccount,
                )
            )
            .retrieve()
            .body<Map<String, Any>>()
            ?.let {
                PayoutClient.PayoutResult(
                    txId = it["tx_id"] as String,
                    status = it["status"] as String,
                )
            } ?: throw IllegalStateException("payout response body was null")
}
