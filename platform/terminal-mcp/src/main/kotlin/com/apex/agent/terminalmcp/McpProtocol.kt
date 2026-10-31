package com.apex.agent.terminalmcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP 协议常量与版本协商。
 *
 * Streamable HTTP 传输（spec 2025-06-18）允许服务器对 POST 回纯 JSON ——
 * 本实现走该最小合规路线（SSE 流可选，服务器可 405 GET）。三值兼容是
 * 必须的：Claude Code 发 2025-06-18、RikkaHub 发 2025-03-26、老客户端发
 * 2024-11-05，客户端发的版本在集合内就原样回显，否则回服务器最新版
 * （客户端不认识则断连 —— 这是 spec 的协商语义）。
 */
object McpProtocol {

    /** 服务器可协商的协议版本全集。 */
    val SUPPORTED = setOf("2024-11-05", "2025-03-26", "2025-06-18")

    /** 服务器最新支持版本（协商失败时的回显值）。 */
    const val LATEST = "2025-06-18"

    /** 协商：请求版本可识别则原样回显，否则回 [LATEST]。 */
    fun negotiate(requested: String?): String =
        if (requested != null && requested in SUPPORTED) requested else LATEST
}

/**
 * MCP 工具描述（tools/list 条目）。
 *
 * @property inputSchema JSON Schema object（type/properties/required）。
 * schema 用 JsonObject 而非 data class —— 各工具形状差异大，schema 即契约。
 */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("inputSchema", inputSchema)
    }
}

/**
 * tools/call 的执行结果。
 *
 * @property text 人类可读文本（structured 为空时的唯一载荷）。
 * @property structured 结构化结果（spec 2025-06-18：必须同时镜像进 text
 * block —— [McpDispatcher] 负责双通道）。
 * @property isError true = 工具执行失败（区别于协议错误 -32602）。
 */
data class McpToolCallOutcome(
    val text: String,
    val structured: JsonObject? = null,
    val isError: Boolean = false,
)

/**
 * 工具面提供者 —— 协议层与终端运行时之间的接缝。
 *
 * PR 1/10 用 [NO_TOOLS] 空实现起步；PR 2/10 起由 TerminalRuntime 适配器
 * 实现（run / list_sessions / send_signal / resize ...）。
 *
 * [find] 独立于 [listTools] 存在的原因：tools/call 需要在派发前判定
 * 「工具名是否注册」（未注册 → 协议错误 -32602），listTools 是全量枚举，
 * find 是点查 —— 适配器可用注册表 O(1) 实现，避免每次调用枚举全量。
 */
interface McpToolProvider {
    suspend fun listTools(): List<McpToolDescriptor>

    suspend fun find(name: String): McpToolDescriptor?

    suspend fun call(name: String, arguments: JsonObject): McpToolCallOutcome
}

/** 空工具面（传输基座阶段 / 服务器离线自检）。 */
object NoToolProvider : McpToolProvider {
    override suspend fun listTools(): List<McpToolDescriptor> = emptyList()
    override suspend fun find(name: String): McpToolDescriptor? = null
    override suspend fun call(name: String, arguments: JsonObject): McpToolCallOutcome =
        McpToolCallOutcome(text = "no tools registered", isError = true)
}
