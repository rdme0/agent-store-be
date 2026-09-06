package com.agentstore.agent.codec

import com.agentstore.agent.dto.internal.AgentManifestDto
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.stereotype.Component
import org.yaml.snakeyaml.LoaderOptions

@Component
class AgentManifestCodec {
    companion object {
        private const val MAX_CODE_POINT_LIMIT = 262_144
        private const val MAX_NESTING_DEPTH = 32
    }

    private val mapper: ObjectMapper

    init {
        val loaderOptions = LoaderOptions().apply {
            maxAliasesForCollections = 0
            codePointLimit = MAX_CODE_POINT_LIMIT
            nestingDepthLimit = MAX_NESTING_DEPTH
            isAllowDuplicateKeys = false
        }
        val factory = YAMLFactory.builder()
            .loaderOptions(loaderOptions)
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build()
        mapper = ObjectMapper(factory)
            .registerKotlinModule()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
    }

    fun read(content: String): AgentManifestDto {
        return mapper.readValue(content, AgentManifestDto::class.java)
    }

    fun write(manifest: AgentManifestDto): String {
        return mapper.writeValueAsString(manifest).replace(
            oldValue = "\r\n",
            newValue = "\n",
        )
    }
}
