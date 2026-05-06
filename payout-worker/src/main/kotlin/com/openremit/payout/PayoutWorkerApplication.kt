package com.openremit.payout

import com.openremit.payout.infrastructure.client.PayoutClientProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka

@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties(PayoutClientProperties::class)
class PayoutWorkerApplication

fun main(args: Array<String>) {
    runApplication<PayoutWorkerApplication>(*args)
}
