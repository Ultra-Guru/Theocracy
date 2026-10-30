package com.apex.agent.core.tools.catalog

import com.apex.agent.core.llm.ToolDefinition
import com.apex.agent.core.tools.AgentTool
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.core.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability Introspection 元工具单测（CapabilityReport）。
 *
 * 覆盖：
 * - CapabilityReport：权限（与 PrivilegeLadder 同源）/ 环境快照（与执行侧
 *   环境门同源）/ 工具核心-目录二分（legacy 剔除）/ 技能-MCP 计数注入与
 *   未知省略 / 扩展梯度完整；
 *   raw URL / McpConnect 带 url 或 command+args+沙箱）、kind 过滤、
 *   limit、无命中与市场不可达的可操作错误。
 */
class CapabilityToolsTest {

    // ── fakes ─────────────────────────────────────────────────

    private class FakeTool(
        override val id: String,
        override val description: String = "Test tool $id"
    ) : AgentTool {
        override val name: String = "Fake $id"
        override val parametersSchema: String = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String = "ok"
    }

    private class FakeRegistry(tools: List<AgentTool>) : ToolRegistry {
        private val map = linkedMapOf<String, AgentTool>()
        init { tools.forEach { map[it.id] = it } }
        override fun register(tool: AgentTool) { map[tool.id] = tool }
        override fun unregister(toolId: String) { map.remove(toolId) }
        override fun getTool(toolId: String): AgentTool? = map[toolId]
        override fun getAllTools(): List<AgentTool> = map.values.toList()
        override fun getToolDefinitions(): List<ToolDefinition> =
            map.values.map { ToolDefinition(it.id, it.description, it.parametersSchema) }
    }

    // ── CapabilityReport ─────────────────────────────────────

    @Test
    fun `report covers privilege environment tools and ladder`() = runBlocking {
        val registry = FakeRegistry(
            listOf(
                FakeTool("Read"),          // CORE
                FakeTool("GithubCreateIssue"), // catalog
                FakeTool("TerminalExecLegacy")        // legacy alias → 剔除
            )
        )
        val env = ToolEnvironmentState().apply {
            set(ToolEnvironmentState.Flags.NETWORK_AVAILABLE, true)
            set(ToolEnvironmentState.Flags.UBUNTU_READY, false)
        }
        val tool = CapabilityReportTool(
            registry = registry,
            environmentState = env,
            privilegeLevel = { "SHIZUKU" }
        )

        val out = tool.execute("{}")

        // 权限段：与 PrivilegeLadder 同源
        assertTrue(out.contains("Privilege: SHIZUKU"))
        assertTrue(out.contains("- CAN: "))
        assertTrue(out.contains("- CANNOT: "))
        assertTrue(out.contains("- Upgrade: "))
        // 环境段：与执行侧环境门同源（flag 名 = ToolEnvironmentState.Flags 常量）
        assertTrue(out.contains("network_available=on"))
        assertTrue(out.contains("ubuntu_ready=off"))
        // 工具段：legacy 剔除 → 2 注册（1 默认加载 + 1 目录）
        assertTrue(out.contains("Tools: 2 registered — 1 loaded by default, 1 more"))
        // 扩展梯度完整
        assertTrue(out.contains("ToolSearch(query)"))
        assertTrue(out.contains("Never claim a task is impossible"))
    }

    @Test
    fun `report privilege flows from provider lambda`() = runBlocking {
        val tool = CapabilityReportTool(
            registry = FakeRegistry(emptyList()),
            environmentState = ToolEnvironmentState(),
            privilegeLevel = { "ROOT" }
        )
        val out = tool.execute("{}")
        assertTrue(out.contains("Privilege: ROOT"))
        // ROOT 无 CANNOT 行
        assertFalse(out.contains("- CANNOT:"))
    }
}
