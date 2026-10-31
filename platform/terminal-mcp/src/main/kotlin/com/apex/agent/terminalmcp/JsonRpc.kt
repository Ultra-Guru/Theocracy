package com.apex.agent.terminalmcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** JSON-RPC 2.0 标准错误码（MCP 在其上复用）。 */
object JsonRpcCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

/**
 * 单条 JSON-RPC 请求（或通知）。
 *
 * @property id null（或 JSON null）表示 notification —— 服务器以 202 应答，
 * 绝不回 JSON-RPC response（MCP Streamable HTTP 传输的硬性规则）。
 */
data class JsonRpcRequest(
    val id: JsonElement?,
    val method: String,
    val params: JsonObject?,
) {
    val isNotification: Boolean get() = id == null || id is JsonNull
}

/**
 * JSON-RPC 2.0 编解码（基于 kotlinx.serialization 的 JsonObject 树操作）。
 *
 * 解析结果三分支见 [Parsed]：非法 JSON / 合法 JSON 但不是合规请求 / 合法请求。
 * 区分前两者是必须的 —— HTTP 层据此回 -32700 与 -32600（spec 2025-06-18
 * transports：拒绝时 4xx + 可选无 id 的 JSON-RPC error body）。
 */
object JsonRpc {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析结果：Ok（合规请求）或 Bad（携带应回给客户端的错误码）。 */
    sealed class Parsed {
        data class Ok(val request: JsonRpcRequest) : Parsed()
        data class Bad(val code: Int, val message: String) : Parsed()
    }

    /** 解析请求体。空体 / 非法 JSON 到 PARSE_ERROR；非对象 / 缺 method / jsonrpc 非 2.0 到 INVALID_REQUEST。 */
    fun parse(body: String): Parsed {
        if (body.isBlank()) return Parsed.Bad(JsonRpcCodes.PARSE_ERROR, "parse error: empty body")
        val element = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            return Parsed.Bad(JsonRpcCodes.PARSE_ERROR, "parse error: malformed JSON")
        } catch (e: IllegalArgumentException) {
            return Parsed.Bad(JsonRpcCodes.PARSE_ERROR, "parse error: malformed JSON")
        }
        if (element !is JsonObject) {
            return Parsed.Bad(JsonRpcCodes.INVALID_REQUEST, "invalid request: body is not a JSON object")
        }
        val version = (element["jsonrpc"] as? JsonPrimitive)?.contentOrNull
        if (version != "2.0") {
            return Parsed.Bad(JsonRpcCodes.INVALID_REQUEST, "invalid request: jsonrpc must be \"2.0\"")
        }
        val method = (element["method"] as? JsonPrimitive)?.contentOrNull
            ?: return Parsed.Bad(JsonRpcCodes.INVALID_REQUEST, "invalid request: missing method")
        val params = element["params"] as? JsonObject
        return Parsed.Ok(JsonRpcRequest(id = element["id"], method = method, params = params))
    }

    /** 构造 success response（id 原样回显 —— 客户端匹配待答请求的唯一锚点）。 */
    fun success(id: JsonElement?, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }.toString()

    /** 构造 error response（id 不可解析时为 null，spec 允许）。 */
    fun error(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()
}
