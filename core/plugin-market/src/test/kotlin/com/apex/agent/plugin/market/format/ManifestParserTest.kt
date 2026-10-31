package com.apex.agent.plugin.market.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ManifestParser] 契约测试：形状层（JSON → 模型）与防御式折叠。
 *
 * 断言值全部按 Spec §3.1 样例与模型默认值核实 —— 不臆造。
 */
class ManifestParserTest {

    /** Spec §3.1 的完整样例（合法输入全字段 round-trip）。 */
    private val specSample = """
        {
          "schemaVersion": "1.0",
          "id": "com.example.meeting-helper",
          "name": "meeting-helper",
          "displayName": "会议助手",
          "version": "1.2.0",
          "description": "自动整理会议纪要并创建待办",
          "author": { "name": "Example Inc", "email": "dev@example.com" },
          "license": "Apache-2.0",
          "category": "productivity",
          "tags": ["meeting", "notes", "mcp"],
          "minAndroidApi": 34,
          "compat": { "claudeMarketplace": true, "opencodePlugin": false },
          "components": {
            "skills": ["./skills/summarize", "./skills/review"],
            "mcpServers": "./mcp/servers.json",
            "appFunctions": "./mcp/appfunctions.json",
            "hooks": "./hooks/"
          },
          "capabilities": [
            { "id": "calendar.read", "risk": "R1", "description": "读取日历事件以生成摘要" },
            { "id": "tasks.write", "risk": "R2", "description": "创建待办事项，每次写入需用户确认" }
          ],
          "dependencies": [],
          "signature": { "algorithm": "ES256", "keyId": "key-2026" }
        }
    """.trimIndent()

    @Test
    fun `spec sample parses all fields`() {
        val m = ManifestParser.parse(specSample)
        assertNotNull("Spec 样例必须解析成功", m)
        m!!
        assertEquals("1.0", m.schemaVersion)
        assertEquals("com.example.meeting-helper", m.id)
        assertEquals("meeting-helper", m.name)
        assertEquals("会议助手", m.displayName)
        assertEquals("1.2.0", m.version)
        assertEquals("Example Inc", m.author.name)
        assertEquals("dev@example.com", m.author.email)
        assertEquals(listOf("meeting", "notes", "mcp"), m.tags)
        assertEquals(34, m.minAndroidApi)
        assertTrue(m.compat.claudeMarketplace)
        assertEquals(2, m.components.skills.size)
        assertEquals("./mcp/servers.json", m.components.mcpServers)
        assertEquals("./mcp/appfunctions.json", m.components.appFunctions)
        assertEquals("./hooks/", m.components.hooks)
        assertEquals(2, m.capabilities.size)
        assertEquals("calendar.read", m.capabilities[0].id)
        assertEquals("R1", m.capabilities[0].risk)
        assertEquals("ES256", m.signature.algorithm)
        assertEquals("key-2026", m.signature.keyId)
    }

    @Test
    fun `empty json object yields all-default manifest`() {
        // 空对象：形状合法 → 全默认值模型（必填性由 Validator 判定，不在这层炸）
        val m = ManifestParser.parse("{}")
        assertNotNull(m)
        assertEquals("", m!!.schemaVersion)
        assertEquals(emptyList<String>(), m.tags)
        assertEquals(emptyList<PluginCapability>(), m.capabilities)
    }

    @Test
    fun `unknown fields are ignored`() {
        // 市场后端扩展字段（futureExt）不炸旧客户端 —— ignoreUnknownKeys 契约
        val m = ManifestParser.parse("""{"schemaVersion": "1.0", "futureExt": {"x": 1}}""")
        assertNotNull(m)
        assertEquals("1.0", m!!.schemaVersion)
    }

    @Test
    fun `malformed json folds to null`() {
        assertNull(ManifestParser.parse("{ not json"))
        assertNull(ManifestParser.parse("""{"id": 123}"""))  // 类型不匹配（id 非 String）
        assertNull(ManifestParser.parse("[1,2,3]"))           // 根非对象
    }

    @Test
    fun `blank input folds to null`() {
        assertNull(ManifestParser.parse(""))
        assertNull(ManifestParser.parse("   \n\t "))
    }

    @Test
    fun `render round-trips parse`() {
        val original = ManifestParser.parse(specSample)!!
        val rendered = ManifestParser.render(original)
        val reparsed = ManifestParser.parse(rendered)
        assertEquals("render → parse 应无损往返", original, reparsed)
    }

    @Test
    fun `type mismatched fields are rejected`() {
        // 显式类型不匹配必须失败（tags 非数组）
        assertNull(ManifestParser.parse("""{"tags": "not-an-array"}"""))
    }
}
