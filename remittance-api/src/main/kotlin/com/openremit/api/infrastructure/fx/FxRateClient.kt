package com.openremit.api.infrastructure.fx

import com.openremit.common.Currency
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.math.BigDecimal
import java.time.Duration

class FxRateUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class FxRateBadRequestException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

interface FxRateClient {
    fun fetch(from: Currency, to: Currency): BigDecimal
}

data class FxRateResponse(val from: String, val to: String, val rate: BigDecimal)

@Component
class RestClientFxRateClient(
    private val fxRateRestClient: RestClient,
    circuitBreakerRegistry: CircuitBreakerRegistry,
    retryRegistry: RetryRegistry,
) : FxRateClient {

    private val circuitBreaker = circuitBreakerRegistry.circuitBreaker(CB_NAME)
    private val retry = retryRegistry.retry(CB_NAME)

    override fun fetch(from: Currency, to: Currency): BigDecimal {
        // Order: Retry(outer) → CircuitBreaker(inner) — each retry attempt is counted
        // by the breaker, so a burst of upstream failures trips OPEN faster.
        // Inner supplier throws RestClientException (matched by retry-exceptions config)
        // so the retry policy can see and retry on it. We wrap into FxRateUnavailableException
        // only after retries are exhausted.
        val cbWrapped = io.github.resilience4j.circuitbreaker.CircuitBreaker
            .decorateSupplier(circuitBreaker) { call(from, to) }
        val retried = io.github.resilience4j.retry.Retry.decorateSupplier(retry, cbWrapped)
        return try {
            retried.get()
        } catch (e: FxRateBadRequestException) {
            throw e
        } catch (e: CallNotPermittedException) {
            throw FxRateUnavailableException("FX circuit breaker open: $from->$to", e)
        } catch (e: RestClientException) {
            throw FxRateUnavailableException("FX rate API failed: $from->$to", e)
        }
    }

    private fun call(from: Currency, to: Currency): BigDecimal {
        val response = fxRateRestClient.get()
            .uri("/rates?from={from}&to={to}", from.name, to.name)
            .retrieve()
            // 4xx는 사용자/요청 측 오류 → retry/CB 카운트에서 제외
            .onStatus(HttpStatusCode::is4xxClientError) { _, res ->
                throw FxRateBadRequestException("FX upstream 4xx: ${res.statusCode} for $from->$to")
            }
            .onStatus(HttpStatusCode::is5xxServerError) { _, res ->
                throw RestClientException("Upstream FX API 5xx: ${res.statusCode}")
            }
            .body(FxRateResponse::class.java)
        if (response == null || response.rate <= BigDecimal.ZERO) {
            throw RestClientException("FX rate response invalid: $from->$to")
        }
        if (response.from != from.name || response.to != to.name) {
            throw RestClientException(
                "FX rate response mismatch: requested $from->$to, got ${response.from}->${response.to}"
            )
        }
        return response.rate
    }

    companion object {
        const val CB_NAME = "fx-rate"
    }
}

@Configuration
class FxRateClientConfig {

    @Bean
    fun fxRateRestClient(
        @Value("\${openremit.fx.base-url}") baseUrl: String,
    ): RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            org.springframework.http.client.SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(2))
                setReadTimeout(Duration.ofSeconds(3))
            }
        )
        .build()
}
