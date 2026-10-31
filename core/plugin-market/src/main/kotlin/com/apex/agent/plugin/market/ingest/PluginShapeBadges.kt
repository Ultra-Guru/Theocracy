package com.apex.agent.plugin.market.ingest

import com.apex.agent.plugin.market.format.AgentPluginManifest

/**
 * 插件形态标签（Spec §4.3/§7.1：详情页形态标注）。
 *
 * 形态由 manifest 的 components 与 compat **严格推导**（不是独立声明 ——
 * 声明与内容漂移的标签是欺骗面；推导保证一致）：
 *
 * | 形态 | 判定 |
 * |---|---|
 * | SKILLS | components.skills 非空（纯工作流） |
 * | MCP | components.mcpServers 非空（远程 server 组合） |
 * | APPFUNCTIONS | components.appFunctions 非空（本地设备函数） |
 * | OPENCODE_HOOK | components.hooks 非空或 compat.opencodePlugin |
 *
 * 全空 → EMPTY（空壳，市场上架拦截位——与 PR-A 的 COMPONENTS_EMPTY
 * warning 同源）。多形态组合按固定顺序输出（UI 标签排序稳定）。
 */
object PluginShapeBadges {

    enum class Shape(val display: String) {
        SKILLS("Skills 工作流"),
        MCP("MCP 远程工具"),
        APPFUNCTIONS("AppFunctions 本地函数"),
        OPENCODE_HOOK("OpenCode Hook"),
        EMPTY("空壳"),
    }

    /** 形态推导（顺序稳定：SKILLS → MCP → APPFUNCTIONS → OPENCODE_HOOK）。 */
    fun of(manifest: AgentPluginManifest): List<Shape> {
        val shapes = mutableListOf<Shape>()
        if (manifest.components.skills.isNotEmpty()) shapes += Shape.SKILLS
        if (manifest.components.mcpServers != null) shapes += Shape.MCP
        if (manifest.components.appFunctions != null) shapes += Shape.APPFUNCTIONS
        if (manifest.components.hooks != null || manifest.compat.opencodePlugin) shapes += Shape.OPENCODE_HOOK
        if (shapes.isEmpty()) shapes += Shape.EMPTY
        return shapes
    }

    /** Spec §4.3 三分类便捷判定：纯指令型（无外部工具）。 */
    fun isSkillsOnly(manifest: AgentPluginManifest): Boolean =
        of(manifest).let { it.size == 1 && it[0] == Shape.SKILLS }

    /** Spec §4.3：Skill + MCP server 组合。 */
    fun isMcpBacked(manifest: AgentPluginManifest): Boolean =
        Shape.MCP in of(manifest) && Shape.SKILLS in of(manifest)

    /** Spec §4.3：Skill + 本地函数组合。 */
    fun isAppFunctionsBacked(manifest: AgentPluginManifest): Boolean =
        Shape.APPFUNCTIONS in of(manifest) && Shape.SKILLS in of(manifest)
}
