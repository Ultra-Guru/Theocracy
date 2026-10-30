package com.apex.agent.slash

import com.apex.agent.plugin.host.functional.FunctionalSlashCommand

/**
 * Runtime context the [SlashCommandRouter] consults to decide how a command
 * should be routed. Currently functional plugin slash commands (Plugin
 * System v2) are needed, but the shape is extensible: future command types
 * plug in by adding fields here.
 *
 * The context is a small immutable value object so routing stays pure and
 * unit-testable — the ViewModel snapshots connection state at dispatch time
 * and passes it in, rather than the router reaching into live managers.
 *
 * @param githubConnected `true` when a GitHub Personal Access Token has been
 *   saved. Surfaced in the system message so the user can see which account
 *   the GitHub context is bound to.
 * @param githubUsername The GitHub login resolved at token-validation time,
 *   or `null` when not connected.
 * @param functionalCommands Plugin System v2：已激活功能性插件的斜杠指令
 *   贡献快照（指令 id → 贡献）。`/plugin:<id>` 命中时路由注入插件声明的
 *   定制 prompt；未命中或调用方未携带（旧路径）退化为通用插件路由文案
 *   ——向后兼容，不破坏既有 APK 插件指令。
 */
data class SlashRouteContext(
    val githubConnected: Boolean = false,
    val githubUsername: String? = null,
    val functionalCommands: Map<String, FunctionalSlashCommand> = emptyMap()
) {
    companion object {
        /** Sentinel used when no live connection state is available. */
        val Empty: SlashRouteContext = SlashRouteContext()
    }
}

/**
 * The UI/system artifacts produced by routing a parsed [SlashCommand]:
 *
 * - [systemMessage]: a single human-readable status line appended to the
 *   chat as an `AgentUiMessage.System` bubble (e.g. `"📦 插件: xxx"`).
 * - [agentPrompt]: the prompt handed to `AgentEngine.execute(...)` so the
 *   LLM can act on the command, including parsed args + user extra text.
 */
data class SlashCommandRoute(
    val systemMessage: String,
    val agentPrompt: String,
    /**
     * 路由来源类别："plugin"，null 表示普通指令。
     * 与 [sourceName] 一起供 UI 渲染流水线横幅与后续工具调用的来源徽章。
     */
    val routeKind: String? = null,
    /** 路由来源名称（指令 id）：插件指令均有值。 */
    val sourceName: String? = null
)

/**
 * Maps a parsed [SlashCommand] into a [SlashCommandRoute], consulting the
 * supplied [SlashRouteContext] for commands that depend on live connection
 * state.
 *
 * The known command types (Plugin) share the same prompt
 * skeleton — only the verb and tool hint differ — so they are built by
 * [buildAgentPrompt]. Unknown commands forward the raw text to the agent
 * verbatim so the user's intent is never silently dropped.
 */
object SlashCommandRouter {

    /**
     * GitHub 原生工具 IDs registered by `ToolModule` when a token is connected.
     * Mirrored here (rather than imported) so the `slash` package stays free
     * of `app`-module dependencies and remains pure-JVM unit-testable. If
     * `ToolModule` adds/removes a GitHub tool, update this list in lockstep.
     */
    private val GITHUB_TOOL_IDS: List<String> = listOf(
        "GithubGetUser",
        "GithubListRepos",
        "GithubReadFile",
        "GithubWriteFile",
        "GithubCreateIssue",
        "GithubListIssues",
        "GithubSearchCode",
        "GithubListBranches",
        "GithubSearchRepos"
    )

    fun route(
        command: SlashCommand,
        context: SlashRouteContext = SlashRouteContext.Empty
    ): SlashCommandRoute = when (command) {
        is SlashCommand.Plugin -> routePlugin(command, context)
        is SlashCommand.Unknown -> SlashCommandRoute(
            systemMessage = "⚡ 指令: ${command.raw.trim()}",
            // Forward the raw input verbatim — the user typed it on purpose
            // and the agent is best placed to interpret free-form commands.
            agentPrompt = command.raw
        )
    }

    /**
     * Plugin routing（Plugin System v2 双形态）：
     *
     * - **功能性插件指令**（[SlashRouteContext.functionalCommands] 命中）：
     *   注入插件声明的定制 prompt（`/plugin:briefing` → 每日简报工作流），
     *   系统消息携带插件名徽章；
     * - **APK 插件 / 未命中**：既有通用路由——插件工具已注册进
     *   ToolRegistry（PluginManager 真实注册），引导模型直接调用插件工具；
     *   不存在时如实说明。
     */
    private fun routePlugin(command: SlashCommand.Plugin, context: SlashRouteContext): SlashCommandRoute {
        val functional = context.functionalCommands[command.id]
        if (functional != null) {
            val promptBody = functional.prompt.ifBlank {
                "插件 ${functional.pluginName} 已激活，其工具已注册进 ToolRegistry —— 请直接调用对应的插件工具完成任务。"
            }
            return SlashCommandRoute(
                systemMessage = "📦 功能插件: ${functional.pluginName} · ${command.id}",
                agentPrompt = command.buildAgentPrompt(
                    verb = promptBody,
                    toolHint = "插件 ${functional.pluginName} 的工具"
                ),
                routeKind = "plugin",
                sourceName = command.id
            )
        }
        return SlashCommandRoute(
            systemMessage = "📦 插件: ${command.id}（工具已注册进 Agent 工具表）",
            agentPrompt = command.buildAgentPrompt(
                verb = "插件 ${command.id} 已加载，其工具已注册进 ToolRegistry —— 请直接调用该插件注册的工具完成任务（可先用 ToolSearch 按插件名检索其工具清单）。若工具不存在，如实告知用户该插件未安装",
                toolHint = "插件工具"
            ),
            routeKind = "plugin",
            sourceName = command.id
        )
    }

    /**
     * Shared prompt builder for the known command types.
     *
     * Shape:
     *
     *     用户触发了快捷指令: /<type>:<id>
     *     <verb>（<toolHint>: <id>）
     *     指令参数: k1=v1, k2=v2    ← only when args present
     *
     *     用户附加要求: <userExtra>   ← only when userExtra present
     */
    private fun SlashCommand.buildAgentPrompt(verb: String, toolHint: String): String = buildString {
        append("用户触发了快捷指令: /").append(type).append(":").append(id).append('\n')
        append(verb).append("（").append(toolHint).append("：").append(id).append("）")
        if (args.isNotEmpty()) {
            append("\n指令参数: ")
            append(args.entries.joinToString(", ") { "${it.key}=${it.value}" })
        }
        if (userExtra.isNotBlank()) {
            append("\n\n用户附加要求: ").append(userExtra)
        }
    }
}
