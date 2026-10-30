package com.apex.agent.platform.terminal.tools.v2

import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.tools.TerminalTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Agent tool: TerminalBackends — T73（后端能力发现）
 *
 * ExecutionBackend.kt 注释指定的 P73 交付物：availability() 的 Agent 出口。
 *
 * 列出所有已注册执行后端及其真实可用性：
 *   - local          ANDROID_LOCAL  恒 READY
 *（Theocracy：linux-ubuntu/PRoot 后端已随 Linux 栈剥离移除）
 *
 * JSON Schema (input):  { }（无参数）
 * JSON Schema (output): { backends: [ { id, runtimeType, available, state, detail? } ] }
 */
class TerminalBackendsTool(
    private val runtime: TerminalRuntime
) : TerminalTool {
    override val id: String = "TerminalBackends"
    override val name: String = id
    override val description: String = """
        Discover available terminal execution backends and their real availability.
        Returns each backend's id (use as TerminalCreate's "backend" parameter), runtime type,
        and state: READY (usable now) / FAILED (with reason).
        Theocracy ships a single "local" backend (the on-device Android shell).
    """.trimIndent()

    override val parametersSchema: String = """
{"type":"object","properties":{},"required":[]}
    """.trimIndent()

    override suspend fun invoke(arguments: String): String {
        // 无参数；容忍调用方传 {} 或多余键
        if (arguments.isNotBlank()) {
            runCatching { Json.parseToJsonElement(arguments).jsonObject }
        }
        val list = runtime.backends()
        return buildJsonObject {
            put("backends", buildJsonArray {
                for (b in list) {
                    add(buildJsonObject {
                        put("id", JsonPrimitive(b.id))
                        put("runtimeType", JsonPrimitive(b.runtimeType))
                        put("available", JsonPrimitive(b.available))
                        put("state", JsonPrimitive(b.state))
                        b.detail?.let { put("detail", JsonPrimitive(it)) }
                    })
                }
            })
        }.toString()
    }
}
