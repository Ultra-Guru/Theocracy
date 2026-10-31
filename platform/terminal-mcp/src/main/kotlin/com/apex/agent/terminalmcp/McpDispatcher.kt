package com.apex.agent.terminalmcp

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * MCP 方法分发器：JSON-RPC 方法 → 协议结果。
 *
 * 支持面（PR 1/10）：initialize / ping / tools/list / tools/call /
 * notifications 全家（202）。resources 与 prompts 不实现 —— 客户端调用
 * 返回 -32601（spec 允许能力面只声明 tools）。
 *
 * 无状态设计：不维护会话簿 —— 每连接自协商，Mcp-Session-Id 仅在
 * initialize 响应上分配（spec：可选实现；缺失时客户端无需回带）。
 */
class McpDispatcher(
    private val serverInfo: ServerInfo,
    private val tools: McpToolProvider,
) {

    /** initialize 响应的 serverInfo 与 instructions 内容。 */
    data class ServerInfo(
        val name: String,
        val version: String,
        val title: String = "Theocracy Terminal",
        val instructions: String = DEFAULT_INSTRUCTIONS,
    )

    /** 分发结果：Respond（200 + JSON-RPC body）或 Accepted（202 无 body，notification 专用）。 */
    sealed class Outcome {
        data class Respond(
            val body: String,
            /** initialize 响应额外携带的 Mcp-Session-Id 头（其余方法为 null）。 */
            val sessionHeader: String? = null,
        ) : Outcome()

        data object Accepted : Outcome()
    }

    suspend fun dispatch(request: JsonRpcRequest): Outcome {
        // notification 一律 202（含未知的 notifications/x —— 拒绝比吞掉更吵，
        // 客户端的扩展通知与本服务器无关）。
        if (request.isNotification) return Outcome.Accepted
        return handleRequest(request)
    }

    private suspend fun handleRequest(request: JsonRpcRequest): Outcome = when (request.method) {
        "initialize" -> initialize(request)
        "ping" -> Outcome.Respond(JsonRpc.success(request.id, buildJsonObject { }))
        "tools/list" -> toolsList(request)
        "tools/call" -> toolsCall(request)
        else -> protocolError(
            request,
            JsonRpcCodes.METHOD_NOT_FOUND,
            "method not found: ${request.method} (this server exposes tools only)",
        )
    }

    private fun initialize(request: JsonRpcRequest): Outcome {
        val requested = (request.params?.get("protocolVersion") as? JsonPrimitive)?.contentOrNull
        val result = buildJsonObject {
            put("protocolVersion", McpProtocol.negotiate(requested))
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
            })
            put("serverInfo", buildJsonObject {
                put("name", serverInfo.name)
                put("title", serverInfo.title)
                put("version", serverInfo.version)
            })
            put("instructions", serverInfo.instructions)
        }
        return Outcome.Respond(
            JsonRpc.success(request.id, result),
            sessionHeader = UUID.randomUUID().toString(),
        )
    }

    private suspend fun toolsList(request: JsonRpcRequest): Outcome {
        val descriptors = tools.listTools()
        val result = buildJsonObject {
            put("tools", JsonArray(descriptors.map { it.toJson() }))
        }
        return Outcome.Respond(JsonRpc.success(request.id, result))
    }

    private suspend fun toolsCall(request: JsonRpcRequest): Outcome {
        val params = request.params
            ?: return protocolError(request, JsonRpcCodes.INVALID_PARAMS, "tools/call requires params")
        val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            ?: return protocolError(request, JsonRpcCodes.INVALID_PARAMS, "tools/call requires string param: name")
        val rawArguments = params["arguments"]
        if (rawArguments != null && rawArguments !is JsonObject && rawArguments !is JsonNull) {
            return protocolError(request, JsonRpcCodes.INVALID_PARAMS, "tools/call arguments must be an object")
        }
        val arguments = rawArguments as? JsonObject ?: JsonObject(emptyMap())
        val descriptor = tools.find(name)
            ?: return protocolError(request, JsonRpcCodes.INVALID_PARAMS, "unknown tool: $name")
        val outcome = try {
            tools.call(descriptor.name, arguments)
        } catch (e: CancellationException) {
            // 协程纪律：取消必须穿透（连接中断时 runBlocking 桥被取消）。
            throw e
        } catch (t: Throwable) {
            // 工具执行异常 → MCP 执行错误（isError=true），不是协议错误 ——
            // Agent 可以读到失败原因并自行修复重试。
            McpToolCallOutcome(
                text = "tool execution failed: ${t.message ?: t.javaClass.simpleName}",
                isError = true,
            )
        }
        // spec 2025-06-18：structuredContent 必须同时在 text block 里回序列化
        // JSON（老客户端只认 text）。
        val textForBlock = outcome.structured?.toString() ?: outcome.text
        val result = buildJsonObject {
            put("content", JsonArray(listOf(buildJsonObject {
                put("type", "text")
                put("text", textForBlock)
            })))
            outcome.structured?.let { put("structuredContent", it) }
            put("isError", outcome.isError)
        }
        return Outcome.Respond(JsonRpc.success(request.id, result))
    }

    private fun protocolError(request: JsonRpcRequest, code: Int, message: String): Outcome =
        Outcome.Respond(JsonRpc.error(request.id, code, message))

    companion object {
        /** initialize instructions —— 给 Agent 客户端的简短用法说明。 */
        const val DEFAULT_INSTRUCTIONS =
            "Theocracy Terminal MCP server: executes commands in a persistent Android " +
                "local shell (mksh PTY) owned by a foreground service. " +
                "Tools: run (blocking execute with waitpid-confirmed exit codes), " +
                "read_screen (semantic screen: live rows, cursor, alternate-screen, cwd, " +
                "scrollback), write (interactive input: passwords, [Y/n] prompts), " +
                "list_sessions, send_signal (foreground process group), resize. " +
                "Omit session_id to reuse the default shared session (cwd/env/aliases persist). " +
                "After a timed-out run, use read_screen to see what the command is waiting for. " +
                "All operations are audit-logged; risky commands may require human confirmation."
    }
}
