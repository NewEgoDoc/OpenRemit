package com.openremit.api.fx

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.openremit.api.TestcontainersConfig
import com.openremit.api.infrastructure.fx.FxRateBadRequestException
import com.openremit.api.infrastructure.fx.FxRateCache
import com.openremit.api.infrastructure.fx.FxRateProvider
import com.openremit.api.infrastructure.fx.FxRateUnavailableException
import com.openremit.api.infrastructure.fx.RestClientFxRateClient
import com.openremit.common.Currency
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@Import(TestcontainersConfig::class)
class FxRateProviderIntegrationTest @Autowired constructor(
    private val provider: FxRateProvider,
    private val cache: FxRateCache,
    private val redissonClient: RedissonClient,
    private val circuitBreakerRegistry: CircuitBreakerRegistry,
) {

    companion object {
        private val wireMock = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            if (!wireMock.isRunning) wireMock.start()
            registry.add("openremit.fx.base-url") { "http://localhost:${wireMock.port()}" }
        }
    }

    @BeforeTest
    fun setup() {
        wireMock.resetAll()
        flushFxKeys()
        circuitBreakerRegistry.circuitBreaker(RestClientFxRateClient.CB_NAME).reset()
    }

    @AfterTest
    fun cleanup() {
        flushFxKeys()
    }

    @Test
    fun `successful response is returned and cached in both tiers`() {
        stubRate("KRW", "USD", "0.000735")

        val rate = provider.rate(Currency.KRW, Currency.USD)

        assertEquals(0, rate.compareTo(BigDecimal("0.000735")))
        assertEquals(0, cache.fresh(Currency.KRW, Currency.USD)!!.compareTo(BigDecimal("0.000735")))
        assertEquals(0, cache.stale(Currency.KRW, Currency.USD)!!.compareTo(BigDecimal("0.000735")))
    }

    @Test
    fun `fresh cache hit skips upstream call`() {
        cache.put(Currency.KRW, Currency.USD, BigDecimal("0.000700"))

        val rate = provider.rate(Currency.KRW, Currency.USD)

        assertEquals(0, rate.compareTo(BigDecimal("0.000700")))
        wireMock.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/rates")))
    }

    @Test
    fun `5xx response retries then falls back to stale cache when present`() {
        cache.put(Currency.KRW, Currency.USD, BigDecimal("0.000700"))
        evictFresh(Currency.KRW, Currency.USD)
        wireMock.stubFor(
            get(urlPathEqualTo("/rates"))
                .willReturn(aResponse().withStatus(500))
        )

        val rate = provider.rate(Currency.KRW, Currency.USD)

        assertEquals(0, rate.compareTo(BigDecimal("0.000700")))
        // 3 retry attempts hit the upstream
        wireMock.verify(3, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/rates")))
    }

    @Test
    fun `5xx with no stale cache throws FxRateUnavailableException`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/rates"))
                .willReturn(aResponse().withStatus(500))
        )

        assertFailsWith<FxRateUnavailableException> {
            provider.rate(Currency.KRW, Currency.USD)
        }
    }

    @Test
    fun `circuit breaker opens after repeated failures and fast-fails subsequent calls`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/rates"))
                .willReturn(aResponse().withStatus(500))
        )

        // Fire enough failing calls to trip the breaker (sliding-window=10, min-calls=5,
        // failure-rate=50%, retry counts each attempt as a separate CB call).
        repeat(3) {
            runCatching { provider.rate(Currency.KRW, Currency.USD) }
        }

        val cb = circuitBreakerRegistry.circuitBreaker(RestClientFxRateClient.CB_NAME)
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN, cb.state)

        // Subsequent call should fast-fail without reaching upstream
        val callsBefore = wireMock.allServeEvents.size
        runCatching { provider.rate(Currency.KRW, Currency.USD) }
        val callsAfter = wireMock.allServeEvents.size
        assertEquals(callsBefore, callsAfter, "CB OPEN must short-circuit the upstream call")
    }

    @Test
    fun `4xx response is not retried and does not affect circuit breaker`() {
        wireMock.stubFor(
            get(urlPathEqualTo("/rates"))
                .willReturn(aResponse().withStatus(404))
        )

        repeat(20) {
            runCatching { provider.rate(Currency.KRW, Currency.USD) }
        }

        // Each call hits upstream exactly once (no retry on 4xx)
        wireMock.verify(20, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/rates")))
        // CB should remain CLOSED — 4xx must not pollute breaker stats
        val cb = circuitBreakerRegistry.circuitBreaker(RestClientFxRateClient.CB_NAME)
        assertEquals(io.github.resilience4j.circuitbreaker.CircuitBreaker.State.CLOSED, cb.state)
        // The provider surface returns FxRateBadRequestException for 4xx
        assertFailsWith<FxRateBadRequestException> {
            provider.rate(Currency.KRW, Currency.USD)
        }
    }

    @Test
    fun `same currency pair returns 1 without calling upstream`() {
        val rate = provider.rate(Currency.KRW, Currency.KRW)

        assertEquals(0, rate.compareTo(BigDecimal.ONE))
        wireMock.verify(0, com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlPathEqualTo("/rates")))
    }

    private fun stubRate(from: String, to: String, rate: String) {
        wireMock.stubFor(
            get(urlPathEqualTo("/rates"))
                .withQueryParam("from", equalTo(from))
                .withQueryParam("to", equalTo(to))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"from":"$from","to":"$to","rate":$rate}""")
                )
        )
    }

    private fun flushFxKeys() {
        val keys = redissonClient.keys.getKeysByPattern("fx:*")
        keys.forEach { redissonClient.getBucket<Any>(it).delete() }
    }

    private fun evictFresh(from: Currency, to: Currency) {
        redissonClient.getBucket<Any>("fx:fresh:$from:$to").delete()
    }
}
