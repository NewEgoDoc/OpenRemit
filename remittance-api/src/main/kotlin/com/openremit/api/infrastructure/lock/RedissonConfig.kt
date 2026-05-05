package com.openremit.api.infrastructure.lock

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(RedissonClient::class)
    fun redissonClient(
        @Value("\${openremit.redis.address}") address: String,
    ): RedissonClient {
        val config = Config()
        config.useSingleServer().address = address
        return Redisson.create(config)
    }
}
