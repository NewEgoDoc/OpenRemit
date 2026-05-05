package com.openremit.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(scanBasePackages = ["com.openremit"])
@EntityScan("com.openremit")
@EnableJpaRepositories("com.openremit")
class RemittanceApiApplication

fun main(args: Array<String>) {
    runApplication<RemittanceApiApplication>(*args)
}
