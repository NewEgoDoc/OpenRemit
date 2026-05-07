package com.openremit.webhook.infrastructure.client

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

interface WebhookClient {
    /**
     * 외부 webhook 엔드포인트로 POST. 2xx 응답이면 성공.
     * 4xx/5xx/IO는 [WebhookSendException]으로 wrap.
     */
    fun send(targetUrl: String, payload: String): Result

    data class Result(val httpStatus: Int)
}

class WebhookSendException(
    val httpStatus: Int?,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@ConfigurationProperties(prefix = "openremit.webhook")
data class WebhookClientProperties(
    val targetUrl: String,
)

@Configuration
class WebhookClientConfig {
    @Bean
    fun webhookRestClient(): RestClient =
        // SimpleClientHttpRequestFactory(JDK HttpURLConnection)를 명시 — RestClient 기본 후보가
        // HTTP/2 negotiate를 시도해 WireMock과 RST_STREAM 호환성 문제를 일으키는 것 방지 (payout-worker 동일).
        RestClient.builder()
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
}

@Component
class RestClientWebhookClient(
    private val webhookRestClient: RestClient,
) : WebhookClient {

    override fun send(targetUrl: String, payload: String): WebhookClient.Result =
        try {
            val response = webhookRestClient.post()
                .uri(targetUrl)
                .header("Content-Type", "application/json")
                .body(payload)
                .retrieve()
                .toBodilessEntity()
            // RestClient.retrieve()는 4xx/5xx만 예외로 처리하므로 3xx 리다이렉트는 여기로 떨어진다.
            // contract는 "2xx만 성공"이므로 비-2xx는 재시도 대상으로 wrap.
            if (!response.statusCode.is2xxSuccessful) {
                throw WebhookSendException(
                    httpStatus = response.statusCode.value(),
                    message = "webhook target responded with non-2xx ${response.statusCode}",
                )
            }
            WebhookClient.Result(httpStatus = response.statusCode.value())
        } catch (e: WebhookSendException) {
            throw e
        } catch (e: RestClientResponseException) {
            throw WebhookSendException(
                httpStatus = e.statusCode.value(),
                message = "webhook target responded with ${e.statusCode}",
                cause = e,
            )
        } catch (e: Exception) {
            throw WebhookSendException(
                httpStatus = null,
                message = e.message ?: e.javaClass.simpleName,
                cause = e,
            )
        }
}
