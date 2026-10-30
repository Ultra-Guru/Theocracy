package com.apex.agent.slash

import com.apex.agent.plugin.host.functional.FunctionalSlashCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SlashCommandRouter].
 *
 * Covers:
 * - The four generic command types (Skill / Mcp-other / Connector / Plugin)
 *   produce the expected system-message prefix and an agent prompt that
 *   embeds args + user extra.
 * - `/mcp:github` is the special case: with a connected context it emits a
 *   prompt that explicitly enumerates the `github_*` tool IDs; with a
 *   disconnected context it short-circuits to `requestGithubConnect = true`
 *   and an empty agent prompt.
 * - Unknown commands forward the raw text verbatim.
 * - `/logic:<mode>`（v1.5 双思考逻辑）是本地路由命令：只产引导性系统消息
 *   且 agentPrompt 恒为空（Coding 屏在路由前截获执行；Agent 屏走到此处
 *   只展示引导不进引擎）。
 * - Plugin System v2：`/plugin:<id>` 命中功能性插件指令快照时注入插件声
 *   明的定制 prompt；未命中 / 空 prompt / 旧调用方不携带快照时既有通用
 *   插件路由零回归。
 */
class SlashCommandRouterTest {

    // ═══════════════════════════════════════════════════════════
    // Generic command types
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Connector route has connector emoji and generic prompt`() {
        val cmd = SlashCommand.Connector(id = "ssh", args = mapOf("host" to "1.2.3.4"))
        val route = SlashCommandRouter.route(cmd)

        assertEquals("🔗 使用连接器: ssh", route.systemMessage)
        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("/connector:ssh"))
        assertTrue(route.agentPrompt.contains("host=1.2.3.4"))
    }

    @Test
    fun `Plugin route has plugin emoji and registered-tools status`() {
        val cmd = SlashCommand.Plugin(id = "pdf_reader")
        val route = SlashCommandRouter.route(cmd)

        // 插件工具桥已激活（IApexPluginHost + PluginManager 真实注册）：
        // 路由词应指向已注册的插件工具，并引导模型直接调用。
        assertEquals("📦 插件: pdf_reader（工具已注册进 Agent 工具表）", route.systemMessage)
        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("/plugin:pdf_reader"))
        assertTrue(route.agentPrompt.contains("已注册进 ToolRegistry"))
        assertFalse(route.agentPrompt.contains("尚未接线"))
    }

    // ═══════════════════════════════════════════════════════════
    // Plugin System v2 — functional plugin command routing
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Functional plugin command injects declared prompt and plugin badge`() {
        val cmd = SlashCommand.Plugin(id = "briefing")
        val context = SlashRouteContext(
            functionalCommands = mapOf(
                "briefing" to FunctionalSlashCommand(
                    commandId = "briefing",
                    pluginId = "functional.daily-briefing",
                    pluginName = "每日简报",
                    title = "每日简报",
                    description = "生成简报",
                    prompt = "使用 briefing_run 工具生成今日简报"
                )
            )
        )
        val route = SlashCommandRouter.route(cmd, context)

        // 命中功能性指令：插件名徽章 + 定制 prompt（路由器不越权改写声明文本）。
        assertTrue(route.systemMessage.contains("功能插件"))
        assertTrue(route.systemMessage.contains("每日简报"))
        assertTrue(route.agentPrompt.contains("/plugin:briefing"))
        assertTrue(route.agentPrompt.contains("使用 briefing_run 工具生成今日简报"))
        assertEquals("plugin", route.routeKind)
        assertEquals("briefing", route.sourceName)
        assertFalse(route.requestGithubConnect)
    }

    @Test
    fun `Functional plugin command with blank prompt falls back to generic tool guidance`() {
        val cmd = SlashCommand.Plugin(id = "pulse")
        val context = SlashRouteContext(
            functionalCommands = mapOf(
                "pulse" to FunctionalSlashCommand(
                    commandId = "pulse",
                    pluginId = "functional.repo-pulse",
                    pluginName = "仓库脉搏",
                    title = "仓库脉搏",
                    description = "",
                    prompt = ""
                )
            )
        )
        val route = SlashCommandRouter.route(cmd, context)

        // 空定制 prompt：退化为「工具已注册，直接调用」的通用引导（与 APK
        // 插件通用路由同语义），不产生空转提示词。
        assertTrue(route.agentPrompt.contains("已注册进 ToolRegistry"))
        assertTrue(route.agentPrompt.contains("仓库脉搏"))
    }

    @Test
    fun `Plugin command not matching functional snapshot keeps generic APK routing`() {
        val cmd = SlashCommand.Plugin(id = "com.apex.agent.plugin.workflow")
        val context = SlashRouteContext(
            functionalCommands = mapOf(
                "briefing" to FunctionalSlashCommand(
                    commandId = "briefing",
                    pluginId = "functional.daily-briefing",
                    pluginName = "每日简报",
                    title = "每日简报",
                    description = "",
                    prompt = "x"
                )
            )
        )
        val route = SlashCommandRouter.route(cmd, context)

        // 未命中（id 是 APK 包名）：既有通用路由零回归。
        assertEquals("📦 插件: com.apex.agent.plugin.workflow（工具已注册进 Agent 工具表）", route.systemMessage)
        assertTrue(route.agentPrompt.contains("已注册进 ToolRegistry"))
    }

    @Test
    fun `Unknown route forwards raw text verbatim to agent`() {
        val cmd = SlashCommand.Unknown(raw = "/help")
        val route = SlashCommandRouter.route(cmd)

        assertEquals("⚡ 指令: /help", route.systemMessage)
        assertEquals("/help", route.agentPrompt)
        assertFalse(route.requestGithubConnect)
    }

    // ═══════════════════════════════════════════════════════════
    // Default context backward-compat
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `route without explicit context still routes connector generically`() {
        // Theocracy：skill / mcp / fusion 命令域已剥离 —— 默认上下文行为
        // 由 Connector（无状态依赖的通用类型）承载断言。
        val cmd = SlashCommand.Connector(id = "ssh")
        val route = SlashCommandRouter.route(cmd)

        assertFalse(route.requestGithubConnect)
        assertTrue(route.agentPrompt.contains("/connector:ssh"))
    }
}
