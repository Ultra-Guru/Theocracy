package com.apex.agent.plugin.market.ingest

import com.apex.agent.plugin.market.format.PluginCompat
import com.apex.agent.plugin.market.format.PluginComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生态摄入兼容契约测试（Spec §6/§9）：
 * Claude marketplace 摄入 / OpenCode npm 识别 / 形态标签 / Hook 契约。
 */
class IngestCompatTest {

    // ── Claude marketplace（Spec §9.1）────────────────────────────

    /** 真实形态样例（.claude-plugin 目录下 marketplace.json 的结构）。 */
    private val claudeMarketplaceJson = """
        {
          "name": "Example Marketplace",
          "owner": { "name": "Example Org" },
          "plugins": [
            {
              "name": "meeting-helper",
              "source": "./meeting-helper",
              "description": "自动整理会议纪要并创建待办",
              "version": "1.2.0",
              "author": { "name": "Example Inc", "email": "dev@example.com" },
              "keywords": ["meeting", "notes"],
              "license": "Apache-2.0",
              "mcpServers": { "calendar": { "command": "npx", "args": ["-y", "cal-server"] } },
              "hooks": { "PreToolUse": [{ "matcher": "Write", "hooks": [] }] }
            },
            {
              "name": "pure-skill-pack",
              "description": "纯技能包",
              "version": "0.3.1",
              "author": "someone",
              "skills": ["skills/review"]
            },
            {
              "name": "Broken Name!",
              "description": "非法 slug —— 应跳过"
            },
            {
              "name": "",
              "description": "无名 —— 应跳过"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `claude marketplace file ingests with source badge`() {
        val file = ClaudeMarketplaceMapper.parse(claudeMarketplaceJson)!!
        val result = ClaudeMarketplaceMapper.mapFile(file)
        assertEquals("2 合法 + 2 跳过", 2, result.manifests.size)
        assertEquals(2, result.skippedEntries)

        val mcp = result.manifests.first { it.name == "meeting-helper" }
        assertEquals("claude-marketplace.meeting-helper", mcp.id)
        assertEquals("1.2.0", mcp.version)
        assertEquals("Example Inc", mcp.author.name)
        assertEquals(listOf("meeting", "notes"), mcp.tags)
        assertTrue("来源徽章", mcp.compat.claudeMarketplace)
        assertEquals("mcp/servers.json", mcp.components.mcpServers)
        assertEquals("hooks", mcp.components.hooks)
        // Claude 格式无风险声明 → 保守合成 R2 capability
        assertEquals("R2", mcp.capabilities.single().risk)
        assertTrue(mcp.capabilities.single().description.contains("保守"))
    }

    @Test
    fun `claude skills-only entry maps without synthesized capability`() {
        val file = ClaudeMarketplaceMapper.parse(claudeMarketplaceJson)!!
        val pure = ClaudeMarketplaceMapper.mapFile(file).manifests
            .first { it.name == "pure-skill-pack" }
        assertEquals("claude-marketplace.pure-skill-pack", pure.id)
        assertEquals("0.3.1", pure.version)
        assertEquals(listOf("skills"), pure.components.skills)
        assertEquals(null, pure.components.mcpServers)
        assertTrue("纯技能包无 mcp/hooks → 不合成 R2", pure.capabilities.isEmpty())
    }

    @Test
    fun `claude author string and object forms both extract`() {
        val objectAuthor = ClaudeMarketplaceMapper.parse(
            """{"plugins": [{"name": "a", "version": "1.0.0", "author": {"name": "Obj Author"}}]}"""
        )!!
        val stringAuthor = ClaudeMarketplaceMapper.parse(
            """{"plugins": [{"name": "b", "version": "1.0.0", "author": "Str Author"}]}"""
        )!!
        assertEquals("Obj Author", ClaudeMarketplaceMapper.mapFile(objectAuthor).manifests.single().author.name)
        assertEquals("Str Author", ClaudeMarketplaceMapper.mapFile(stringAuthor).manifests.single().author.name)
    }

    @Test
    fun `malformed claude json folds to null`() {
        assertNull(ClaudeMarketplaceMapper.parse("{ not json"))
        assertNull(ClaudeMarketplaceMapper.parse(""))
    }

    @Test
    fun `mapped claude manifest passes repository validation`() {
        // 关键不变量：摄入产物必须能过本仓 ManifestValidator（镜像源不豁免门禁）
        val file = ClaudeMarketplaceMapper.parse(claudeMarketplaceJson)!!
        val validated = ClaudeMarketplaceMapper.mapFile(file).manifests.map {
            com.apex.agent.plugin.market.format.ManifestValidator.validate(it)
        }
        assertTrue("摄入产物必须全过语义校验", validated.all { it.isValid })
    }

    // ── OpenCode npm（Spec §6/§9.2）───────────────────────────────

    @Test
    fun `opencode plugin with object entry is recognized`() {
        val pkg = """
            {
              "name": "my-opencode-plugin",
              "version": "2.1.0",
              "description": "OpenCode 增强插件",
              "license": "MIT",
              "keywords": ["lint", "format"],
              "opencode": { "plugin": "./plugin.ts", "hooks": { "tool.execute.before": [] } }
            }
        """.trimIndent()
        val m = OpenCodePluginMapper.parseAndMap(pkg)
        assertNotNull("opencode 对象入口必须识别", m)
        assertEquals("opencode-npm.my-opencode-plugin", m!!.id)
        assertEquals("2.1.0", m.version)
        assertTrue(m.compat.opencodePlugin)
        assertEquals("hooks", m.components.hooks)
        assertEquals("R2", m.capabilities.single().risk)
    }

    @Test
    fun `opencode plugin with string entry is recognized`() {
        val pkg = """{"name": "str-entry", "version": "1.0.0", "opencode": "./index.js"}"""
        val m = OpenCodePluginMapper.parseAndMap(pkg)
        assertNotNull(m)
        assertTrue(m!!.compat.opencodePlugin)
        assertEquals(null, m.components.hooks)  // 字符串入口无 hooks 细分
        assertTrue(m.capabilities.isEmpty())
    }

    @Test
    fun `plain npm package without opencode entry is not ingested`() {
        val pkg = """{"name": "left-pad", "version": "1.3.0", "description": "普通 npm 包"}"""
        assertNull("不伪装适配（Spec §9.2）", OpenCodePluginMapper.parseAndMap(pkg))
    }

    @Test
    fun `malformed npm json folds to null`() {
        assertNull(OpenCodePluginMapper.parseAndMap("not json"))
    }

    // ── 形态标签（Spec §4.3/§7.1）─────────────────────────────────

    private fun manifest(
        skills: List<String> = emptyList(),
        mcp: String? = null,
        appFn: String? = null,
        hooks: String? = null,
        opencode: Boolean = false,
    ) = com.apex.agent.plugin.market.format.AgentPluginManifest(
        id = "com.example.x", name = "x", version = "1.0.0",
        components = PluginComponents(skills = skills, mcpServers = mcp, appFunctions = appFn, hooks = hooks),
        compat = PluginCompat(opencodePlugin = opencode),
    )

    @Test
    fun `shape badges derive from components strictly`() {
        assertEquals(
            listOf(PluginShapeBadges.Shape.SKILLS),
            PluginShapeBadges.of(manifest(skills = listOf("skills/s"))),
        )
        assertEquals(
            listOf(PluginShapeBadges.Shape.MCP),
            PluginShapeBadges.of(manifest(mcp = "mcp/servers.json")),
        )
        assertEquals(
            listOf(PluginShapeBadges.Shape.EMPTY),
            PluginShapeBadges.of(manifest()),
        )
        // 多形态组合按固定顺序
        assertEquals(
            listOf(PluginShapeBadges.Shape.SKILLS, PluginShapeBadges.Shape.MCP, PluginShapeBadges.Shape.OPENCODE_HOOK),
            PluginShapeBadges.of(manifest(skills = listOf("skills/s"), mcp = "mcp/servers.json", opencode = true)),
        )
    }

    @Test
    fun `spec three-way classification helpers`() {
        val skillsOnly = manifest(skills = listOf("skills/s"))
        val mcpBacked = manifest(skills = listOf("skills/s"), mcp = "mcp/servers.json")
        val appFnBacked = manifest(skills = listOf("skills/s"), appFn = "mcp/appfunctions.json")
        assertTrue(PluginShapeBadges.isSkillsOnly(skillsOnly))
        assertFalse(PluginShapeBadges.isSkillsOnly(mcpBacked))
        assertTrue(PluginShapeBadges.isMcpBacked(mcpBacked))
        assertTrue(PluginShapeBadges.isAppFunctionsBacked(appFnBacked))
        assertFalse(PluginShapeBadges.isMcpBacked(appFnBacked))
    }

    // ── Hook Adapter 契约（Spec §6）───────────────────────────────

    @Test
    fun `hook contract supported events`() {
        assertTrue(HookAdapterContract.supports("tool.execute.before"))
        assertTrue(HookAdapterContract.supports("tool.execute.after"))
        assertTrue(HookAdapterContract.supports("session.created"))
        assertTrue(HookAdapterContract.supports("session.idle"))
        assertFalse("tui 不支持（无 TUI）", HookAdapterContract.supports("tui.render"))
        assertFalse("未知事件保守不支持", HookAdapterContract.supports("future.event"))
    }

    @Test
    fun `hook compat report separates supported and unavailable`() {
        val report = HookAdapterContract.evaluate(
            listOf("tool.execute.before", "tui.render", "shell.env.setup")
        )
        assertEquals(listOf("tool.execute.before"), report.supportedEvents)
        assertEquals(listOf("tui.render", "shell.env.setup"), report.unsupportedEvents)
        assertFalse("含不支持项 → 不标「兼容」", report.fullyCompatible)
    }

    @Test
    fun `fully compatible report`() {
        val report = HookAdapterContract.evaluate(listOf("session.created", "session.idle"))
        assertTrue(report.fullyCompatible)
        assertTrue(report.unsupportedEvents.isEmpty())
    }
}
