package com.openremit.api

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class RemittanceApiApplication

fun main(args: Array<String>) {
    runApplication<RemittanceApiApplication>(*args)
}
