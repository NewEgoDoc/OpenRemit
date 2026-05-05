package com.openremit.api.infrastructure.fx

import com.openremit.common.Currency
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class FxRateProvider(
    private val client: FxRateClient,
    private val cache: FxRateCache,
) {
    private val log = LoggerFactory.getLogger(FxRateProvider::class.java)

    fun rate(from: Currency, to: Currency): BigDecimal {
        if (from == to) return BigDecimal.ONE

        cache.fresh(from, to)?.let { return it }

        return try {
            val rate = client.fetch(from, to)
            cache.put(from, to, rate)
            rate
        } catch (e: FxRateUnavailableException) {
            cache.stale(from, to)?.let { stale ->
                log.warn("FX upstream down for {}->{}; serving stale rate={}", from, to, stale)
                return stale
            }
            log.error("FX upstream down for {}->{} and no stale cache available", from, to)
            throw e
        }
    }
}
