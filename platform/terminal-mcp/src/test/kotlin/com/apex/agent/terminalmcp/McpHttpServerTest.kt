package com.apex.agent.terminalmcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_BAD_REQUEST
import java.net.HttpURLConnection.HTTP_FORBIDDEN
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_UNAUTHORIZED
import java.net.URL

/**
 * McpHttpServer 端到端 JVM 单测：真实 NanoHTTPD 实例（127.0.0.1 临时端口）+
 * HttpURLConnection 直打 —— 覆盖 MCP Streamable HTTP 最小合规全集：
 * 握手 / 鉴权 / Origin / 方法路由 / 错误码 / CORS / 202 语义。
 */
class McpHttpServerTest {

    private lateinit var server: McpHttpServer
    private var port: Int = 0
    private val token = "test-token-0123456789abcdef"

    @Before
    fun setUp() {
        server = McpHttpServer(
            port = 0,
            tokenProvider = { token },
            dispatcher = McpDispatcher(
                McpDispatcher.ServerInfo(name = "theocracy-terminal", version = "0.1.0"),
                NoToolProvider,
            ),
        )
        server.start(20_000, true)
        port = server.listeningPort
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ── 帮助函数：HttpURLConnection 直打（JVM 零依赖客户端）──

    private data class Reply(val status: Int, val body: String?, val sessionHeader: String?)

    private fun post(body: String, headers: Map<String, String> = emptyMap()): Reply {
        val conn = URL("http://127.0.0.1:$port/mcp").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json, text/event-stream")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val status = conn.responseCode
        val text = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
        val session = conn.getHeaderField("Mcp-Session-Id")
        conn.disconnect()
        return Reply(status, text, session)
    }

    private fun get(path: String = "/mcp", headers: Map<String, String> = emptyMap()): Reply {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val status = conn.responseCode
        val text = (conn.errorStream ?: conn.inputStream)?.bufferedReader()?.use { it.readText() }
        conn.disconnect()
        return Reply(status, text, null)
    }

    private fun auth() = mapOf("Authorization" to "Bearer $token")

    private fun initializeBody(version: String = "2025-06-18") =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version","capabilities":{},"clientInfo":{"name":"junit","version":"0"}}}"""

    // ── 握手与协议 ──

    @Test
    fun `initialize returns 200 negotiated version and session header`() {
        val reply = post(initializeBody(), auth())
        assertEquals(HTTP_OK, reply.status)
        val result = Json.parseToJsonElement(reply.body!!).jsonObject["result"]!!.jsonObject
        assertEquals("2025-06-18", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("theocracy-terminal", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNotNull(reply.sessionHeader)
        assertTrue(reply.sessionHeader!!.isNotEmpty())
    }

    @Test
    fun `initialize echoes older protocol version`() {
        val reply = post(initializeBody(version = "2024-11-05"), auth())
        assertEquals(HTTP_OK, reply.status)
        val result = Json.parseToJsonElement(reply.body!!).jsonObject["result"]!!.jsonObject
        assertEquals("2024-11-05", result["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ping after initialize answers empty result`() {
        post(initializeBody(), auth())
        val reply = post("""{"jsonrpc":"2.0","id":2,"method":"ping"}""", auth())
        assertEquals(HTTP_OK, reply.status)
        assertEquals(0, Json.parseToJsonElement(reply.body!!).jsonObject["result"]!!.jsonObject.size)
    }

    @Test
    fun `notification receives 202 with empty body`() {
        val reply = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", auth())
        assertEquals(202, reply.status)
    }

    @Test
    fun `unknown method returns jsonrpc error 32601`() {
        val reply = post("""{"jsonrpc":"2.0","id":3,"method":"resources/list"}""", auth())
        assertEquals(HTTP_OK, reply.status)
        val error = Json.parseToJsonElement(reply.body!!).jsonObject["error"]!!.jsonObject
        assertEquals(-32601, error["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `tools list is empty at transport foundation stage`() {
        val reply = post("""{"jsonrpc":"2.0","id":4,"method":"tools/list"}""", auth())
        assertEquals(HTTP_OK, reply.status)
        val result = Json.parseToJsonElement(reply.body!!).jsonObject["result"]!!.jsonObject
        assertEquals(0, result["tools"]!!.jsonArray.size)
    }

    @Test
    fun `unsupported mcp-protocol-version header is rejected with 400`() {
        post(initializeBody(), auth())
        val reply = post(
            """{"jsonrpc":"2.0","id":5,"method":"ping"}""",
            auth() + mapOf("Mcp-Protocol-Version" to "2099-01-01"),
        )
        assertEquals(HTTP_BAD_REQUEST, reply.status)
    }

    // ── 错误码 ──

    @Test
    fun `malformed json body is 400 with parse error`() {
        val reply = post("{not valid json", auth())
        assertEquals(HTTP_BAD_REQUEST, reply.status)
        val error = Json.parseToJsonElement(reply.body!!).jsonObject["error"]!!.jsonObject
        assertEquals(-32700, error["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `valid json but invalid rpc envelope is 400 with invalid request`() {
        val reply = post("""{"id":9,"method":"ping"}""", auth())
        assertEquals(HTTP_BAD_REQUEST, reply.status)
        val error = Json.parseToJsonElement(reply.body!!).jsonObject["error"]!!.jsonObject
        assertEquals(-32600, error["code"]!!.jsonPrimitive.content.toInt())
    }

    // ── 安全 ──

    @Test
    fun `missing token is 401`() {
        val reply = post("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")
        assertEquals(HTTP_UNAUTHORIZED, reply.status)
    }

    @Test
    fun `wrong token is 401`() {
        val reply = post(
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            mapOf("Authorization" to "Bearer wrong"),
        )
        assertEquals(HTTP_UNAUTHORIZED, reply.status)
    }

    @Test
    fun `query parameter token is accepted as fallback`() {
        val conn = URL("http://127.0.0.1:$port/mcp?token=$token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use {
            it.write("""{"jsonrpc":"2.0","id":1,"method":"ping"}""".toByteArray())
        }
        assertEquals(HTTP_OK, conn.responseCode)
        conn.disconnect()
    }

    @Test
    fun `non-local origin is 403`() {
        val reply = post(
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            auth() + mapOf("Origin" to "http://evil.example.com"),
        )
        assertEquals(HTTP_FORBIDDEN, reply.status)
    }

    @Test
    fun `local origin is allowed`() {
        val reply = post(
            """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
            auth() + mapOf("Origin" to "http://localhost:3000"),
        )
        assertEquals(HTTP_OK, reply.status)
    }

    // ── 方法路由 ──

    @Test
    fun `get mcp is 405`() {
        val reply = get(headers = auth())
        assertEquals(405, reply.status)
    }

    @Test
    fun `unknown path is 404`() {
        val reply = get(path = "/other")
        assertEquals(404, reply.status)
    }

    @Test
    fun `unauthenticated get is still 401 not 405`() {
        // 鉴权先于方法路由 —— 未授权客户端探测不到 /mcp 的方法面。
        val reply = get()
        assertEquals(HTTP_UNAUTHORIZED, reply.status)
    }
}
