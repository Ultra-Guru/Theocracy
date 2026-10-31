package com.apex.agent.terminalmcp

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpDispatcher 单测：方法路由 / 版本协商 / 工具面派发 / notification 语义。
 * 手写 fake 工具面（无 mock 框架，全仓纪律）。
 */
class McpDispatcherTest {

    private class FakeTools : McpToolProvider {
        override suspend fun listTools(): List<McpToolDescriptor> = listOf(
            McpToolDescriptor(
                name = "echo",
                description = "echoes the x argument",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    putJsonObject("properties") {
                        put("x", buildJsonObject { put("type", "string") })
                    }
                },
            ),
        )

        override suspend fun find(name: String): McpToolDescriptor? =
            listTools().firstOrNull { it.name == name }

        override suspend fun call(name: String, arguments: JsonObject): McpToolCallOutcome =
            McpToolCallOutcome(
                text = "echo",
                structured = buildJsonObject { put("echoed", arguments["x"]?.jsonPrimitive?.content ?: "") },
            )
    }

    private fun dispatcher(tools: McpToolProvider = NoToolProvider) = McpDispatcher(
        McpDispatcher.ServerInfo(name = "theocracy-terminal", version = "0.1.0"),
        tools,
    )

    private fun request(
        method: String,
        params: JsonObject? = null,
        id: kotlinx.serialization.json.JsonElement? = kotlinx.serialization.json.JsonPrimitive(1),
    ) = JsonRpcRequest(id = id, method = method, params = params)

    private fun bodyOf(outcome: McpDispatcher.Outcome.Respond): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(outcome.body).jsonObject

    @Test
    fun `initialize negotiates requested version and assigns session header`() = runTest {
        val params = buildJsonObject { put("protocolVersion", "2025-03-26") }
        val outcome = dispatcher().dispatch(request("initialize", params)) as McpDispatcher.Outcome.Respond
        val result = bodyOf(outcome)["result"]!!.jsonObject
        assertEquals("2025-03-26", result["protocolVersion"]!!.jsonPrimitive.content)
        assertNotNull(outcome.sessionHeader)
        assertEquals("theocracy-terminal", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
    }

    @Test
    fun `initialize falls back to latest for unknown version`() = runTest {
        val params = buildJsonObject { put("protocolVersion", "1999-01-01") }
        val outcome = dispatcher().dispatch(request("initialize", params)) as McpDispatcher.Outcome.Respond
        val result = bodyOf(outcome)["result"]!!.jsonObject
        assertEquals(McpProtocol.LATEST, result["protocolVersion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ping returns empty result`() = runTest {
        val outcome = dispatcher().dispatch(request("ping")) as McpDispatcher.Outcome.Respond
        assertEquals(0, bodyOf(outcome)["result"]!!.jsonObject.size)
    }

    @Test
    fun `notifications are accepted without response`() = runTest {
        val notification = JsonRpcRequest(id = null, method = "notifications/initialized", params = null)
        val outcome = dispatcher().dispatch(notification)
        assertTrue(outcome is McpDispatcher.Outcome.Accepted)
    }

    @Test
    fun `unknown method yields method not found`() = runTest {
        val outcome = dispatcher().dispatch(request("resources/list")) as McpDispatcher.Outcome.Respond
        val error = bodyOf(outcome)["error"]!!.jsonObject
        assertEquals(JsonRpcCodes.METHOD_NOT_FOUND, error["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `tools list enumerates provider descriptors`() = runTest {
        val outcome = dispatcher(FakeTools()).dispatch(request("tools/list")) as McpDispatcher.Outcome.Respond
        val tools = bodyOf(outcome)["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools call unknown tool is invalid params`() = runTest {
        val params = buildJsonObject { put("name", "run") }
        val outcome = dispatcher().dispatch(request("tools/call", params)) as McpDispatcher.Outcome.Respond
        val error = bodyOf(outcome)["error"]!!.jsonObject
        assertEquals(JsonRpcCodes.INVALID_PARAMS, error["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `tools call passes arguments and mirrors structured into text`() = runTest {
        val params = buildJsonObject {
            put("name", "echo")
            put("arguments", buildJsonObject { put("x", "hello") })
        }
        val outcome = dispatcher(FakeTools()).dispatch(request("tools/call", params)) as McpDispatcher.Outcome.Respond
        val result = bodyOf(outcome)["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        val structured = result["structuredContent"]!!.jsonObject
        assertEquals("hello", structured["echoed"]!!.jsonPrimitive.content)
        // 双通道：text block 必须是 structured 的序列化镜像
        val text = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals(structured.toString(), text)
    }

    @Test
    fun `tools call execution failure becomes isError not protocol error`() = runTest {
        val params = buildJsonObject { put("name", "anything") }
        // ThrowingTools.find 恒 null 会先撞 unknown tool —— 直接用注册了工具但
        // call 抛异常的 provider 形态验证 dispatcher 的异常折叠。
        val tools = object : McpToolProvider {
            override suspend fun listTools() = listOf(
                McpToolDescriptor("t", "throws", buildJsonObject { put("type", "object") }),
            )
            override suspend fun find(name: String) = listTools().firstOrNull()
            override suspend fun call(name: String, arguments: JsonObject): McpToolCallOutcome =
                throw IllegalStateException("boom")
        }
        val outcome = dispatcher(tools).dispatch(request("tools/call", params)) as McpDispatcher.Outcome.Respond
        val result = bodyOf(outcome)["result"]!!.jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("boom"))
    }
}
