package com.agentstore

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.data.jpa.repository.config.EnableJpaAuditing

@SpringBootApplication
@EnableAsync
@EnableJpaAuditing
class AgentStoreApplication

fun main(args: Array<String>) {
    runApplication<AgentStoreApplication>(*args)
}
