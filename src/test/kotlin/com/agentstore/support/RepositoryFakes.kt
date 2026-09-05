package com.agentstore.support

import com.agentstore.agent.model.entity.AgentVersionReadiness
import com.agentstore.agent.repository.AgentVersionReadinessRepository
import java.lang.reflect.Proxy
import java.util.Optional

/** Explicit in-memory boundary for isolated service tests; no mock framework is involved. */
class ExplicitProxy<T : Any>(type: Class<T>) {
    private val answers = mutableMapOf<String, (Array<Any?>?) -> Any?>()

    val value: T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, method, args ->
        answers[method.name]?.invoke(args) ?: defaultValue(method.returnType)
    } as T

    fun answer(methodName: String, answer: (Array<Any?>?) -> Any?) {
        answers[methodName] = answer
    }
}

fun emptyReadinessRepository(): AgentVersionReadinessRepository {
    return Proxy.newProxyInstance(
        AgentVersionReadinessRepository::class.java.classLoader,
        arrayOf(AgentVersionReadinessRepository::class.java),
    ) { _, method, args ->
        when (method.name) {
            "save", "saveAndFlush" -> args?.firstOrNull()
            "findById", "findOne" -> Optional.empty<AgentVersionReadiness>()
            "findAllById", "findAllByStatus" -> emptyList<AgentVersionReadiness>()
            "existsById" -> false
            "count" -> 0L
            "delete", "deleteById", "deleteAll", "flush" -> Unit
            else -> defaultValue(method.returnType)
        }
    } as AgentVersionReadinessRepository
}

private fun defaultValue(type: Class<*>): Any? {
    return when {
        !type.isPrimitive -> null
        type == Boolean::class.javaPrimitiveType -> false
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type == Short::class.javaPrimitiveType -> 0.toShort()
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
