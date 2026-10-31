package com.apex.agent.terminalmcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JsonRpc 编解码单测：三分支解析（Ok / PARSE_ERROR / INVALID_REQUEST）+
 * response 构造形状。
 */
class JsonRpcTest {

    @Test
    fun `malformed json is parse error`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":1,"method"=""")
        assertTrue(parsed is JsonRpc.Parsed.Bad)
        assertEquals(JsonRpcCodes.PARSE_ERROR, (parsed as JsonRpc.Parsed.Bad).code)
    }

    @Test
    fun `empty body is parse error`() {
        val parsed = JsonRpc.parse("   ")
        assertEquals(JsonRpcCodes.PARSE_ERROR, (parsed as JsonRpc.Parsed.Bad).code)
    }

    @Test
    fun `array body is invalid request`() {
        val parsed = JsonRpc.parse("""[{"jsonrpc":"2.0"}]""")
        assertEquals(JsonRpcCodes.INVALID_REQUEST, (parsed as JsonRpc.Parsed.Bad).code)
    }

    @Test
    fun `missing jsonrpc field is invalid request`() {
        val parsed = JsonRpc.parse("""{"id":1,"method":"ping"}""")
        assertEquals(JsonRpcCodes.INVALID_REQUEST, (parsed as JsonRpc.Parsed.Bad).code)
    }

    @Test
    fun `wrong jsonrpc version is invalid request`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")
        assertEquals(JsonRpcCodes.INVALID_REQUEST, (parsed as JsonRpc.Parsed.Bad).code)
    }

    @Test
    fun `missing method is invalid request`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":1}""")
        assertEquals(JsonRpcCodes.INVALID_REQUEST, (parsed as JsonRpc.Parsed.Bad).code)
    }

    @Test
    fun `valid request parses with id method_params`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"run"}}""")
        val ok = parsed as JsonRpc.Parsed.Ok
        assertEquals("tools/call", ok.request.method)
        assertEquals(JsonPrimitive(42), ok.request.id)
        assertEquals("run", ok.request.params?.get("name")?.jsonPrimitive?.content)
        assertFalse(ok.request.isNotification)
    }

    @Test
    fun `request without id is notification`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
        val ok = parsed as JsonRpc.Parsed.Ok
        assertNull(ok.request.id)
        assertTrue(ok.request.isNotification)
    }

    @Test
    fun `explicit json null id is treated as notification`() {
        val parsed = JsonRpc.parse("""{"jsonrpc":"2.0","id":null,"method":"notifications/cancelled"}""")
        assertTrue((parsed as JsonRpc.Parsed.Ok).request.isNotification)
    }

    @Test
    fun `success response echoes id and carries result`() {
        val body = JsonRpc.success(JsonPrimitive(7), buildJsonObject { put("ok", true) })
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("2.0", obj["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(7, obj["id"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, obj["result"]!!.jsonObject["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `error response carries code and message`() {
        val body = JsonRpc.error(null, JsonRpcCodes.METHOD_NOT_FOUND, "nope")
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals(
            JsonRpcCodes.METHOD_NOT_FOUND,
            obj["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt(),
        )
    }
}
