package com.apex.agent.core.tools.catalog

import com.apex.agent.core.tools.ToolAnnotations
import com.apex.agent.core.tools.ToolArguments
import com.apex.agent.core.tools.ToolCategory
import com.apex.agent.core.tools.ToolEnvironmentState
import com.apex.agent.core.tools.ToolErrorCode
import com.apex.agent.core.tools.ToolMetadata
import com.apex.agent.core.tools.ToolRegistry
import com.apex.agent.core.tools.ToolResult
import com.apex.agent.core.tools.ToolRisk
import com.apex.agent.core.tools.ToolSchema
import com.apex.agent.core.tools.toolSchema
import com.apex.agent.core.tools.builtin.BaseTool

/**
 * # Capability Introspection — 自省元工具
 *
 * 根因（「agent 不懂自己能干什么」）：
 * - `ToolList` 只覆盖工具注册表，看不到权限/环境/技能/MCP 全貌；
 *   「不知道自己有什么工具可以安装什么」；
 * - 没有「先自省、再扩展」的第一反动作。
 *
 * 能力自省元工具：
 * - [CapabilityReportTool]（`CapabilityReport`）——一键自省：权限阶梯
 *   （CAN/CANNOT/升级路径，与系统提示词同源 [PrivilegeLadder]）、实时
 *   环境快照（与执行侧环境门同源 [ToolEnvironmentState]）、工具库存 +
 *   扩展梯度；
 *
 * 依赖注入契约：等级经构造 lambda 注入（纯 JVM 可测，无 Android 依赖）。
 */
class CapabilityReportTool(
    private val registry: ToolRegistry,
    private val environmentState: ToolEnvironmentState,
    /** 当前权限等级（"ROOT"/"SHIZUKU"/"NORMAL_SHELL"；未知折叠见 [PrivilegeLadder.normalize]）。 */
    private val privilegeLevel: () -> String = { PrivilegeLadder.LEVEL_NORMAL_SHELL },
    /**
     * GitHub 连接快照（v3 S3）：`"login|repo"`（repo 为 `-` = 未设置默认
     * 仓库）/ null = 未连接。null 默认值 = GitHub 行整段省略（既有构造点
     * 零改动，向后兼容）；core 经 lambda 拿快照，不依赖 app 层
     * GithubTokenManager，保持纯 JVM 可测。
     */
    private val githubStateProvider: (() -> String?)? = null
) : BaseTool(
    id = "CapabilityReport",
    name = "Capability Report",
    description = """
        One-call self-check of everything you can do right now: privilege
        level (with CAN/CANNOT lists and the upgrade path), live environment
        flags, and tool inventory (loaded vs catalog). Ends with the expansion
        ladder for acquiring missing capabilities. Call this FIRST when unsure
        whether something is possible, when a capability question comes up, or
        right after a permission error — instead of guessing or giving up.
    """.trimIndent(),
    declaredSchema = toolSchema { }
) {
    override fun buildMetadata(): ToolMetadata = ToolMetadata.meta(id) {
        category(ToolCategory.UTILITY)
        risk(ToolRisk.LOW)
        tag("introspection", "capability", "privilege")
        annotations(ToolAnnotations.readOnly())
    }

    override suspend fun executeStructured(arguments: String): ToolResult {
        val info = PrivilegeLadder.infoFor(privilegeLevel())

        val allTools = registry.getAllTools()
            .filterNot { ToolTierPolicy.isLegacyAlias(it.id) }
        val coreCount = allTools.count { ToolTierPolicy.isCore(it.id) }
        val catalogCount = allTools.size - coreCount

        return ToolResult.ok(
            buildString {
                appendLine("== Capability Report ==")
                appendLine("Privilege: ${info.summary}")
                appendLine("- CAN: ${info.can.joinToString("; ")}.")
                if (info.cannot.isNotEmpty()) {
                    appendLine("- CANNOT: ${info.cannot.joinToString("; ")}.")
                }
                appendLine("- Upgrade: ${info.upgradeHint}")
                appendLine()
                appendLine("Environment: ${environmentState.summary()}")
                appendLine(
                    "Tools: ${allTools.size} registered — $coreCount loaded by default, " +
                        "$catalogCount more via ToolSearch(query) → ToolOpen(tool_name)."
                )
                // v3 S3：GitHub 连接行（宿主未注入 provider 时整行省略——
                // 纯 JVM 测试/既有构造点零变化）。快照解析防御式：lambda 抛
                // 异常或格式异常折叠为 not connected（不教模型假事实）。
                if (githubStateProvider != null) {
                    val snapshot = runCatching { githubStateProvider() }.getOrNull()
                    val parts = snapshot?.split('|', limit = 2)
                    val login = parts?.getOrNull(0)?.takeIf { it.isNotBlank() }
                    if (login != null) {
                        val repo = parts.getOrNull(1)
                            ?.takeUnless { it.isNullOrBlank() || it == "-" }
                        val repoPart = if (repo != null) " (default repo: $repo)" else ""
                        appendLine("GitHub: connected as $login$repoPart — github_* tools ready.")
                    } else {
                        appendLine("GitHub: not connected — github_* tools need a PAT (ask the user).")
                    }
                }
                // v3 双工位作用域（事实性描述）：本工具不感知设置开关
                // （AgentSettings.mcpScopeIsolation），只陈述引擎计划层的
                // 隔离机制本身；开关语义见 docs/scope-isolation.md。
                appendLine(
                    "Workspace isolation: scope enforcement at engine plan level " +
                        "(per workspace: agent/coding/all)."
                )
                appendLine()
                appendLine("Expansion ladder (when a capability is missing):")
                appendLine("1. ToolSearch(query) → ToolOpen(tool_name) — any registered tool")
                appendLine("2. Privilege wall → ask the user to raise the level (see upgrade hint above)")
                appendLine("Never claim a task is impossible before trying these routes.")
            }.trim()
        )
    }
}
