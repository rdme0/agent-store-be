package com.agentstore.agent.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.yaml.snakeyaml.LoaderOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AgentManifestConfiguration {
    companion object {
        const val MANIFEST_YAML_MAPPER = "manifestYamlMapper"
    }

    @Bean
    @Qualifier(MANIFEST_YAML_MAPPER)
    fun manifestYamlMapper(): ObjectMapper {
        val loaderOptions = LoaderOptions().apply {
            maxAliasesForCollections = 0
            codePointLimit = 262_144
            nestingDepthLimit = 32
            isAllowDuplicateKeys = false
        }
        val factory = YAMLFactory.builder()
            .loaderOptions(loaderOptions)
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build()
        return ObjectMapper(factory)
            .registerKotlinModule()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    }
}
