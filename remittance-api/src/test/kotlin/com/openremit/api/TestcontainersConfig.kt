package com.openremit.api

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer<*> =
        MySQLContainer("mysql:8.0")
            .withDatabaseName("openremit")
            .withUsername("test")
            .withPassword("test")

    @Bean
    fun redisContainer(): GenericContainer<*> =
        GenericContainer(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379)

    @Bean(destroyMethod = "shutdown")
    fun redissonClient(redisContainer: GenericContainer<*>): RedissonClient {
        if (!redisContainer.isRunning) redisContainer.start()
        val config = Config()
        config.useSingleServer().address =
            "redis://${redisContainer.host}:${redisContainer.getMappedPort(6379)}"
        return Redisson.create(config)
    }
}
