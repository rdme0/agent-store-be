package com.agentstore

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.scheduling.annotation.EnableAsync

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
@EnableJpaAuditing
class AgentStoreApplication

fun main(args: Array<String>) {
    runApplication<AgentStoreApplication>(*args)
}
