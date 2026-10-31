package com.apex.agent.terminalmcp

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.runBlocking

/** NanoHTTPD 2.3.1 无内建 MIME_JSON 常量（仅 MIME_PLAINTEXT / MIME_HTML）—— 本地补充。 */
private const val MIME_JSON = "application/json"

/**
 * MCP Streamable HTTP 传输服务器（NanoHTTPD，仅绑定 127.0.0.1）。
 *
 * 最小合规路线（spec 2025-06-18 transports）：
 * - POST /mcp：请求 → 200 + application/json（纯 JSON 响应，不做 SSE 流）；
 *   notification → 202 无 body；非法 envelope → 400 + JSON-RPC error。
 * - GET / DELETE / PUT / HEAD /mcp → 405（spec：服务器可不实现流式 GET）。
 * - initialize 响应附 Mcp-Session-Id 头；非 initialize 请求带
 *   Mcp-Protocol-Version 头且值不被支持时 → 400（无头时按 spec 假定
 *   2025-03-26，服务器无版本相关行为差异，直接放行）。
 *
 * 安全：Bearer 令牌（[McpAuth.isTokenValid] 常量时间比较；支持 ?token=
 * 查询参数兜底）+ Origin 白名单（[McpAuth.isLocalOrigin]，防 DNS
 * rebinding）。OPTIONS 预检放行（浏览器客户端），其余方法全部过鉴权。
 *
 * ── 线程契约（与 platform:terminal 的 TerminalIpcController 同范式）──
 * NanoHTTPD 每连接一线程；suspend 工具面经 [runBlocking] 在该工作线程上
 * 有界收敛。这是 blocking 传输层对 suspend 协议层的既定契约妥协 —— 工具
 * 执行自身的超时预算由工具面（PR 2/10 起 TerminalRuntime 的 WaitEngine
 * timeoutMs）负责，传输层不加二次上限，避免长任务（编译输出 tail）被误杀。
 */
class McpHttpServer(
    port: Int,
    private val tokenProvider: () -> String,
    private val dispatcher: McpDispatcher,
) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        // CORS 预检：浏览器客户端（如有）的 OPTIONS 先行放行 —— 鉴权在
        // 实际方法上执行，预检不泄露任何状态。
        if (session.method == Method.OPTIONS) {
            return withCors(newFixedLengthResponse(Status.NO_CONTENT, MIME_PLAINTEXT, ""))
        }
        return try {
            withCors(route(session))
        } catch (t: Throwable) {
            // 防御式 IO 纪律：任何未预期异常折叠为 500 JSON-RPC error，
            // 绝不让异常裸奔断连（Agent 拿到可读错误才能自修复）。
            withCors(
                newFixedLengthResponse(
                    Status.INTERNAL_ERROR,
                    MIME_JSON,
                    JsonRpc.error(null, JsonRpcCodes.INTERNAL_ERROR, "internal error: ${t.message ?: t.javaClass.simpleName}"),
                ),
            )
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        if (uri != "/mcp") {
            return json(Status.NOT_FOUND, JsonRpc.error(null, JsonRpcCodes.INVALID_REQUEST, "not found: ${session.uri} (only /mcp)"))
        }
        // Origin 校验先于鉴权：不合法源在消耗任何鉴权逻辑前被拒。
        val origin = session.headers["origin"]
        if (origin != null && !McpAuth.isLocalOrigin(origin)) {
            return json(Status.FORBIDDEN, JsonRpc.error(null, JsonRpcCodes.INVALID_REQUEST, "origin not allowed"))
        }
        if (!authorized(session)) {
            val response = json(
                Status.UNAUTHORIZED,
                JsonRpc.error(null, JsonRpcCodes.INVALID_REQUEST, "missing or invalid bearer token"),
            )
            response.addHeader("WWW-Authenticate", "Bearer")
            return response
        }
        return when (session.method) {
            Method.POST -> handlePost(session)
            else -> methodNotAllowed(session.method)
        }
    }

    private fun handlePost(session: IHTTPSession): Response {
        val body = try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"] ?: ""
        } catch (e: ResponseException) {
            // NanoHTTPD 解析失败（坏 chunked / 超限）→ 400，body 不再尝试。
            return json(Status.BAD_REQUEST, JsonRpc.error(null, JsonRpcCodes.PARSE_ERROR, "bad request body"))
        } catch (e: Exception) {
            return json(Status.BAD_REQUEST, JsonRpc.error(null, JsonRpcCodes.PARSE_ERROR, "bad request body"))
        }
        return when (val parsed = JsonRpc.parse(body)) {
            is JsonRpc.Parsed.Bad -> json(
                Status.BAD_REQUEST,
                JsonRpc.error(null, parsed.code, parsed.message),
            )
            is JsonRpc.Parsed.Ok -> dispatch(session, parsed.request)
        }
    }

    private fun dispatch(session: IHTTPSession, request: JsonRpcRequest): Response {
        if (request.method != "initialize") {
            val versionHeader = session.headers["mcp-protocol-version"]
            if (versionHeader != null && versionHeader !in McpProtocol.SUPPORTED) {
                return json(
                    Status.BAD_REQUEST,
                    JsonRpc.error(request.id, JsonRpcCodes.INVALID_REQUEST, "unsupported Mcp-Protocol-Version: $versionHeader"),
                )
            }
        }
        val outcome = runBlocking { dispatcher.dispatch(request) }
        return when (outcome) {
            is McpDispatcher.Outcome.Accepted ->
                newFixedLengthResponse(Status.ACCEPTED, MIME_JSON, "")
            is McpDispatcher.Outcome.Respond -> {
                val response = json(Status.OK, outcome.body)
                outcome.sessionHeader?.let { response.addHeader("Mcp-Session-Id", it) }
                response
            }
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val expected = tokenProvider()
        val bearer = McpAuth.extractBearer(session.headers["authorization"])
        if (bearer != null) return McpAuth.isTokenValid(expected, bearer)
        // ?token= 查询参数兜底：部分轻量客户端只支持 URL 配置。
        val queryToken = session.parameters["token"]?.firstOrNull()
        return McpAuth.isTokenValid(expected, queryToken)
    }

    private fun methodNotAllowed(method: Method): Response {
        val response = json(
            Status.METHOD_NOT_ALLOWED,
            JsonRpc.error(null, JsonRpcCodes.INVALID_REQUEST, "method not allowed: $method (only POST)"),
        )
        response.addHeader("Allow", "POST, OPTIONS")
        return response
    }

    private fun json(status: Status, body: String): Response =
        newFixedLengthResponse(status, MIME_JSON, body)

    private fun withCors(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS, DELETE")
        response.addHeader(
            "Access-Control-Allow-Headers",
            "Authorization, Content-Type, Accept, Mcp-Session-Id, Mcp-Protocol-Version, Last-Event-ID",
        )
        response.addHeader("Access-Control-Expose-Headers", "Mcp-Session-Id")
        return response
    }
}
