package com.openremit.api.infrastructure.fx

import com.openremit.common.Currency
import org.redisson.api.RedissonClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration

@Component
class FxRateCache(
    private val redissonClient: RedissonClient,
    @Value("\${openremit.fx.cache.fresh-ttl}") private val freshTtl: Duration,
    @Value("\${openremit.fx.cache.stale-ttl}") private val staleTtl: Duration,
) {
    fun fresh(from: Currency, to: Currency): BigDecimal? =
        redissonClient.getBucket<String>(freshKey(from, to)).get()?.let(::BigDecimal)

    fun stale(from: Currency, to: Currency): BigDecimal? =
        redissonClient.getBucket<String>(staleKey(from, to)).get()?.let(::BigDecimal)

    fun put(from: Currency, to: Currency, rate: BigDecimal) {
        val value = rate.toPlainString()
        redissonClient.getBucket<String>(freshKey(from, to)).set(value, freshTtl)
        redissonClient.getBucket<String>(staleKey(from, to)).set(value, staleTtl)
    }

    private fun freshKey(from: Currency, to: Currency) = "fx:fresh:$from:$to"
    private fun staleKey(from: Currency, to: Currency) = "fx:stale:$from:$to"
}
