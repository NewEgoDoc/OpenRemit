package com.openremit.reconcile

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ReconcilerApplication

fun main(args: Array<String>) {
    runApplication<ReconcilerApplication>(*args)
}
