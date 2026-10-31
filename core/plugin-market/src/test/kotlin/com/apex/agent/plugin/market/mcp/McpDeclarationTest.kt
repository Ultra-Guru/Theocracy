package com.apex.agent.plugin.market.mcp

import com.apex.agent.plugin.market.pkg.PackageBuilder
import com.apex.agent.plugin.market.pkg.PackageReadResult
import com.apex.agent.plugin.market.pkg.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 声明层契约测试（Spec §5.1）：servers.json 解析/校验、
 * 注册表引用计数、appfunctions.json 校验、非 https 硬门槛。
 */
class McpDeclarationTest {

    // ── McpServerDeclarationCodec ────────────────────────────────

    @Test
    fun `servers file parses and sanitizes valid entries`() {
        val json = """
            {"schema": "apex-mcp-servers-v1", "servers": [
              {"id": "weather", "transport": "https", "url": "https://api.example.com/mcp",
               "auth": {"type": "apikey", "hint": "API Key"}, "description": "天气查询"},
              {"id": "repo", "transport": "sse", "url": "https://mcp.github.com/sse",
               "auth": {"type": "oauth"}, "description": "仓库检索"}
            ]}
        """.trimIndent()
        val file = McpServerDeclarationCodec.parse(json)!!
        val sanitized = McpServerDeclarationCodec.sanitize(file)
        assertEquals(2, sanitized.servers.size)
        assertTrue(sanitized.rejections.isEmpty())
        assertEquals("weather", sanitized.servers[0].id)
        assertEquals("https", sanitized.servers[0].transport)
        assertEquals("apikey", sanitized.servers[0].auth.type)
        assertEquals("sse", sanitized.servers[1].transport)
    }

    @Test
    fun `non-https url is hard rejected`() {
        // Spec §5 硬门槛：非 https 一律剔除
        val json = """
            {"schema": "apex-mcp-servers-v1", "servers": [
              {"id": "insecure", "transport": "https", "url": "http://api.example.com/mcp"},
              {"id": "weird", "transport": "ws", "url": "wss://x.example.com"},
              {"id": "ok", "transport": "https", "url": "https://ok.example.com/mcp"}
            ]}
        """.trimIndent()
        val sanitized = McpServerDeclarationCodec.sanitize(McpServerDeclarationCodec.parse(json)!!)
        assertEquals(listOf("ok"), sanitized.servers.map { it.id })
        assertEquals(2, sanitized.rejections.size)
        assertTrue(sanitized.rejections[0].contains("url 非 https"))
        assertTrue(sanitized.rejections[1].contains("transport 非法"))
    }

    @Test
    fun `duplicate ids and empty ids are rejected`() {
        val json = """
            {"schema": "apex-mcp-servers-v1", "servers": [
              {"id": "dup", "transport": "https", "url": "https://a.example.com"},
              {"id": "dup", "transport": "https", "url": "https://b.example.com"},
              {"id": "", "transport": "https", "url": "https://c.example.com"}
            ]}
        """.trimIndent()
        val sanitized = McpServerDeclarationCodec.sanitize(McpServerDeclarationCodec.parse(json)!!)
        assertEquals(1, sanitized.servers.size)  // 第一个 dup 存活
        assertEquals(2, sanitized.rejections.size)
    }

    @Test
    fun `invalid auth type is rejected`() {
        val json = """
            {"schema": "apex-mcp-servers-v1", "servers": [
              {"id": "x", "transport": "https", "url": "https://x.example.com",
               "auth": {"type": "basic-auth"}}
            ]}
        """.trimIndent()
        val sanitized = McpServerDeclarationCodec.sanitize(McpServerDeclarationCodec.parse(json)!!)
        assertTrue(sanitized.servers.isEmpty())
        assertTrue(sanitized.rejections.single().contains("auth.type 非法"))
    }

    @Test
    fun `malformed servers json folds to null`() {
        assertNull(McpServerDeclarationCodec.parse("{ broken"))
        assertNull(McpServerDeclarationCodec.parse(""))
    }

    // ── McpServerRegistry ─────────────────────────────────────────

    private fun declaration(id: String, url: String = "https://$id.example.com/mcp") =
        McpServerDeclaration(id = id, transport = "https", url = url, description = "d")

    @Test
    fun `registry reference counting keeps shared endpoints until all owners uninstall`() {
        val registry = McpServerRegistry()
        val shared = declaration("shared", "https://shared.example.com/mcp")
        registry.install("com.example.plugin-a", listOf(shared, declaration("a-only")))
        registry.install("com.example.plugin-b", listOf(shared))

        assertEquals(setOf("shared", "a-only"), registry.all().map { it.id }.toSet())
        assertTrue("共享端点已声明", registry.isDeclaredUrl("https://shared.example.com/mcp"))

        val removedByA = registry.uninstall("com.example.plugin-a")
        assertEquals(setOf("a-only"), removedByA.toSet())
        assertTrue("plugin-b 仍持有 shared —— 不应撤除", registry.isDeclaredUrl("https://shared.example.com/mcp"))

        val removedByB = registry.uninstall("com.example.plugin-b")
        assertEquals(setOf("shared"), removedByB.toSet())
        assertEquals("全部持有者卸载后端点撤除", false, registry.isDeclaredUrl("https://shared.example.com/mcp"))
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun `later install overrides same id declaration`() {
        val registry = McpServerRegistry()
        registry.install("plugin-a", listOf(declaration("svc", "https://old.example.com")))
        registry.install("plugin-b", listOf(declaration("svc", "https://new.example.com")))
        assertEquals("https://new.example.com", registry.findById("svc")!!.url)
        assertTrue("旧 url 已被覆盖替换", !registry.isDeclaredUrl("https://old.example.com"))
        assertTrue(registry.isDeclaredUrl("https://new.example.com"))
    }

    @Test
    fun `install from package wires servers json`() {
        val serversJson = """
            {"schema": "apex-mcp-servers-v1", "servers": [
              {"id": "weather", "transport": "https", "url": "https://api.weather.com/mcp"}
            ]}
        """.trimIndent()
        val builder = PackageBuilder().manifest(PackageBuilder.sampleManifest())
            .file("mcp/servers.json", serversJson)
            .file("skills/summarize/SKILL.md", "---\nname: summarize\n---\nbody")
        val pkg = (PluginPackageReader.read(builder.build()) as PackageReadResult.Success).pkg

        val registry = McpServerRegistry()
        val result = registry.installFromPackage(pkg)
        assertEquals(listOf("weather"), result.installed.map { it.id })
        assertTrue(registry.isDeclaredUrl("https://api.weather.com/mcp"))
    }

    // ── AppFunctionBindingCodec ───────────────────────────────────

    @Test
    fun `appfunctions file parses and validates`() {
        val json = """
            {"schema": "apex-appfunctions-v1", "functions": [
              {"qualifiedName": "com.example.calendar/createEvent", "risk": "R2",
               "description": "创建日历事件", "inputSchema": {"type": "object"}},
              {"qualifiedName": "com.example.calendar/listEvents", "risk": "R1",
               "description": "列出日历事件"},
              {"qualifiedName": "bad name", "risk": "R0", "description": "非法名"},
              {"qualifiedName": "com.example.calendar/readEvent", "risk": "R9", "description": "非法风险"}
            ]}
        """.trimIndent()
        val file = AppFunctionBindingCodec.parse(json)!!
        val sanitized = AppFunctionBindingCodec.sanitize(file)
        assertEquals(2, sanitized.functions.size)
        assertEquals(2, sanitized.rejections.size)
        // toolName 缺省取函数段
        assertEquals("createEvent", sanitized.functions[0].toolName)
        assertEquals("listEvents", sanitized.functions[1].toolName)
        assertTrue(sanitized.functions[0].inputSchema != null)
        assertEquals(null, sanitized.functions[1].inputSchema)
    }

    @Test
    fun `malformed appfunctions json folds to null`() {
        assertNull(AppFunctionBindingCodec.parse("[1,2]"))
        assertNull(AppFunctionBindingCodec.parse("not json"))
    }
}
