package com.apex.agent.terminalmcp.tools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP 工具入参读取助手：snake_case 键 + 宽容类型（数字字符串互认）。
 *
 * 内部 agent 工具的既有惯例（platform:terminal tools/v2 的 longArg/stringArg
 * 别名宽容）在 MCP 面对齐 —— 外部 Agent 客户端生成的参数形状不可控，
 * 宽容读取比严格拒绝更利于 Agent 自修复。
 */
internal fun JsonObject.stringArg(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key] ?: continue
        if (value is JsonNull) continue
        return value.jsonPrimitive.contentOrNull
    }
    return null
}

internal fun JsonObject.longArg(vararg keys: String): Long? {
    for (key in keys) {
        val value = this[key] ?: continue
        if (value is JsonNull) continue
        val content = value.jsonPrimitive.contentOrNull ?: continue
        return content.toLongOrNull()
    }
    return null
}

internal fun JsonObject.intArg(vararg keys: String): Int? = longArg(*keys)?.toInt()

internal fun JsonObject.boolArg(vararg keys: String): Boolean? {
    for (key in keys) {
        val value = this[key] ?: continue
        if (value is JsonNull) continue
        val content = value.jsonPrimitive.contentOrNull ?: continue
        return when (content.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
    return null
}
