package com.openremit.webhook

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class WebhookDispatcherTestcontainersConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer<*> =
        MySQLContainer("mysql:8.0")
            .withDatabaseName("openremit")
            .withUsername("test")
            .withPassword("test")

    companion object {
        // Spring Boot 4.0의 @ServiceConnection이 ConfluentKafkaContainer를 인식하지 않으므로
        // singleton container + System property 주입으로 bootstrap-servers를 노출 (payout-worker 동일 패턴).
        val kafka: ConfluentKafkaContainer =
            ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                .also { it.start() }

        init {
            System.setProperty("spring.kafka.bootstrap-servers", kafka.bootstrapServers)
        }
    }
}
