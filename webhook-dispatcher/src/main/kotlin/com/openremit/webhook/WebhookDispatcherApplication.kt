package com.openremit.webhook

import com.openremit.webhook.application.WebhookBackoffProperties
import com.openremit.webhook.infrastructure.client.WebhookClientProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableKafka
@EnableScheduling
@EnableConfigurationProperties(WebhookClientProperties::class, WebhookBackoffProperties::class)
class WebhookDispatcherApplication

fun main(args: Array<String>) {
    runApplication<WebhookDispatcherApplication>(*args)
}
