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
 * - Plugin command types produce the expected system-message prefix and an
 *   agent prompt that embeds args + user extra.
 * - Unknown commands forward the raw text verbatim.
 * - Plugin System v2：`/plugin:<id>` 命中功能性插件指令快照时注入插件声
 *   明的定制 prompt；未命中 / 空 prompt / 旧调用方不携带快照时既有通用
 *   插件路由零回归。
 *
 * Theocracy：connector / skill / mcp / logic / fusion 命令类型已随五域
 * 剥离移除（Skill 路由的 emit 流水线、MCP 连接门控、连接器路由均不再
 * 存在），此处只保留现存路由面的回归。
 */
class SlashCommandRouterTest {

    // ═══════════════════════════════════════════════════════════
    // Generic command types
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `Plugin route has plugin emoji and registered-tools status`() {
        val cmd = SlashCommand.Plugin(id = "pdf_reader")
        val route = SlashCommandRouter.route(cmd)

        // 插件工具桥已激活（IApexPluginHost + PluginManager 真实注册）：
        // 路由词应指向已注册的插件工具，并引导模型直接调用。
        assertEquals("📦 插件: pdf_reader（工具已注册进 Agent 工具表）", route.systemMessage)
        assertTrue(route.agentPrompt.contains("/plugin:pdf_reader"))
        assertTrue(route.agentPrompt.contains("已注册进 ToolRegistry"))
        assertFalse(route.agentPrompt.contains("尚未接线"))
    }

    @Test
    fun `Plugin route forwards args and user extra into prompt`() {
        val cmd = SlashCommand.Plugin(
            id = "pdf_reader",
            args = mapOf("file" to "/sdcard/a.pdf"),
            userExtra = "summarize page 3"
        )
        val route = SlashCommandRouter.route(cmd)

        assertTrue(route.agentPrompt.contains("/plugin:pdf_reader"))
        assertTrue(route.agentPrompt.contains("file=/sdcard/a.pdf"))
        assertTrue(route.agentPrompt.contains("用户附加要求: summarize page 3"))
    }

    @Test
    fun `Plugin route carries plugin routeKind and source name for pipeline badge`() {
        // routeKind/sourceName 是流水线横幅与来源徽章的数据源。
        val cmd = SlashCommand.Plugin(id = "pdf_reader")
        val route = SlashCommandRouter.route(cmd)

        assertEquals("plugin", route.routeKind)
        assertEquals("pdf_reader", route.sourceName)
    }

    @Test
    fun `Unknown route forwards raw text verbatim to agent`() {
        val cmd = SlashCommand.Unknown(raw = "/help")
        val route = SlashCommandRouter.route(cmd)

        assertEquals("⚡ 指令: /help", route.systemMessage)
        assertEquals("/help", route.agentPrompt)
        // 普通指令不携带流水线元数据（无横幅）。
        assertEquals(null, route.routeKind)
        assertEquals(null, route.sourceName)
    }

    @Test
    fun `removed domain command types degrade to Unknown routing`() {
        // 五域剥离后这些类型不再有专属路由 —— 解析层已降级为 Unknown，
        // 路由层对 Unknown 一律原文透传，不产生误导性的专属提示词。
        val parsed = SlashCommand.Unknown(raw = "/connector:ssh")
        val route = SlashCommandRouter.route(parsed)

        assertEquals("/connector:ssh", route.agentPrompt)
        assertEquals(null, route.routeKind)
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
}
