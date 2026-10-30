package com.apex.agent.di

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 终端工具执行器预算回归测试（Ubuntu 栈剥离后收口）：
 *
 * 终端类工具的执行器预算必须 ≥ 工具自身内部预算 —— 否则
 * `DefaultToolRunPolicyResolver` 的 mutating() 60s 推断会先杀掉工具
 * （长命令明明要了 300s 却在 60s 收到超时，docs/ubuntu-environment-exploration.md
 * §6 的历史教训；Ubuntu 环境工具已随 Linux 栈移除，原则对 TerminalExec 依然成立）。
 *
 * 本测试锁住三件事：
 *  1. 存活的长预算终端工具**必须**在覆盖表里（新增终端工具忘了配策略会被抓住）；
 *  2. 每个策略的超时 ≥ 对应工具的内部预算常量；
 *  3. 终端工具不做盲重试。
 */
class TerminalToolPolicyTest {

    /** 工具自身内部预算（与源码常量对齐，漂移时这里要同步）。 */
    private val internalBudgetsMs = mapOf(
        // TerminalExecTool schema 上限 timeout_ms = 600_000
        "TerminalExec" to 600_000L,
        // TerminalDiagnostics smokeTest 探针 timeoutMs = 120_000（余量同 TerminalExec 口径）
        "TerminalDiagnostics" to 120_000L
    )

    @Test
    fun `all long-budget terminal tools are covered by explicit policies`() {
        val mustCover = listOf(
            "ShellExecute",
            "TerminalExec",
            "TerminalExecLegacy",
            "TerminalDiagnostics",
            "TerminalWait"
        )
        for (id in mustCover) {
            assertNotNull(
                "工具 $id 缺少执行器超时策略覆盖（将回落 60s mutating 预算被截杀）",
                TERMINAL_TOOL_RUN_POLICIES[id]
            )
        }
    }

    @Test
    fun `executor budget covers tool-internal budget with margin`() {
        for ((id, internalMs) in internalBudgetsMs) {
            val policy = TERMINAL_TOOL_RUN_POLICIES[id]
            assertNotNull("工具 $id 缺少策略", policy)
            assertTrue(
                "工具 $id 执行器预算 ${policy!!.timeoutMs}ms < 内部预算 $internalMs" +
                    "ms —— 执行器会先于工具自身超时逻辑杀掉调用",
                policy.timeoutMs >= internalMs
            )
        }
    }

    @Test
    fun `terminal tools never blind-retry`() {
        val noRetryIds = TERMINAL_TOOL_RUN_POLICIES.keys -
            setOf("Screenshot")  // 唯一允许一次重试的例外（系统限速补偿）
        for (id in noRetryIds) {
            assertEquals(
                "工具 $id 不应盲重试",
                0,
                TERMINAL_TOOL_RUN_POLICIES[id]!!.maxRetries
            )
        }
    }

    @Test
    fun `resolver applies the override map and mutating default stays 60s`() {
        // 守护测试：默认推断确实是 60s —— 覆盖表存在的意义（否则无 bug 可修）。
        val resolver = com.apex.agent.core.tools.DefaultToolRunPolicyResolver(
            overrides = TERMINAL_TOOL_RUN_POLICIES
        )
        val fakeMutating = FakeEnvTool(
            "terminal.future.tool",
            annotations = com.apex.agent.core.tools.ToolAnnotations.mutating()
        )
        assertEquals(60_000L, resolver.resolve(fakeMutating).timeoutMs)

        // 覆盖后预算生效
        assertEquals(
            TERMINAL_TOOL_RUN_POLICIES["TerminalExec"]!!.timeoutMs,
            resolver.resolve(FakeEnvTool("TerminalExec")).timeoutMs
        )
    }

    private class FakeEnvTool(
        override val id: String,
        private val annotations: com.apex.agent.core.tools.ToolAnnotations? = null
    ) : com.apex.agent.core.tools.AgentTool {
        override val name: String = id
        override val description: String = "fake terminal tool"
        override val parametersSchema: String = "{}"
        override val metadata: com.apex.agent.core.tools.ToolMetadata =
            if (annotations == null) com.apex.agent.core.tools.ToolMetadata.infer(id)
            else com.apex.agent.core.tools.ToolMetadata(
                id, com.apex.agent.core.tools.ToolCategory.TERMINAL,
                com.apex.agent.core.tools.ToolRisk.MEDIUM, emptyList(), annotations
            )

        override suspend fun execute(arguments: String): String = "{}"
    }
}
