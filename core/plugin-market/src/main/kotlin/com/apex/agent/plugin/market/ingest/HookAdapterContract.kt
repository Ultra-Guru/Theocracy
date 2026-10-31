package com.apex.agent.plugin.market.ingest

/**
 * OpenCode Hook Adapter 兼容范围契约（Spec §6：受限兼容层）。
 *
 * ## 契约定位
 *
 * Android 不运行 Node.js/Bun —— hook 脚本经嵌入式 JS 引擎（QuickJS）
 * 在**受限沙箱**执行。本对象冻结「支持什么/不支持什么/沙箱边界」
 * 的机器可读契约（详情页「移动端不可用」标注的数据源 + 未来
 * Adapter 实现的验收基线）。JS 引擎接线是后续里程碑（M6），
 * 契约先行让摄入端可以在引擎就绪前就正确标注兼容性。
 */
object HookAdapterContract {

    /** hook 事件（OpenCode 事件面）。 */
    enum class HookEvent(val wireName: String) {
        /** 工具执行前（可拦截/修改参数 —— 管线 Schema 段之后、Policy 段之前）。 */
        TOOL_EXECUTE_BEFORE("tool.execute.before"),

        /** 工具执行后（结果观测 —— Audit 段挂点）。 */
        TOOL_EXECUTE_AFTER("tool.execute.after"),

        /** 会话创建。 */
        SESSION_CREATED("session.created"),

        /** 会话空闲。 */
        SESSION_IDLE("session.idle"),
    }

    /**
     * 首版支持的 hook（Spec §6 兼容范围）。
     * 不支持面（显式披露而非静默失败 —— Spec §9.2「移动端不可用」标注）：
     * - tui.* 全系（Android 无 TUI）；
     * - shell.env 注入（安全边界 —— 沙箱无宿主环境变量访问权）。
     */
    val SUPPORTED: Set<HookEvent> = setOf(
        HookEvent.TOOL_EXECUTE_BEFORE,
        HookEvent.TOOL_EXECUTE_AFTER,
        HookEvent.SESSION_CREATED,
        HookEvent.SESSION_IDLE,
    )

    /** 沙箱能力边界（Spec §6：脚本无网络/文件系统，仅宿主受限 API）。 */
    enum class SandboxBoundary(val description: String) {
        NO_NETWORK("无网络访问 —— fetch/XHR/WebSocket 不可用"),
        NO_FILE_SYSTEM("无文件系统 —— Node fs 全系不可用"),
        HOST_BRIDGED_API_ONLY("仅宿主暴露的受限桥接 API（审批后的能力面）"),
        NO_PROCESS_ENV("无进程环境变量 —— shell.env 注入不支持"),
    }

    /** 事件是否支持（未知 wireName 一律不支持 —— 保守披露）。 */
    fun supports(wireName: String): Boolean =
        SUPPORTED.any { it.wireName == wireName }

    /**
     * 插件的 hooks 兼容性评估：manifest 的 hooks 组件声明的**事件清单**
     * （由摄入端从 hook 文件/声明提取）中，支持/不支持分别列出 ——
     * 详情页渲染「兼容 OpenCode hooks：a、b；移动端不可用：c」。
     */
    fun evaluate(declaredHookEvents: List<String>): HookCompatReport {
        val supported = declaredHookEvents.filter { supports(it) }
        val unsupported = declaredHookEvents.filter { !supports(it) }
        return HookCompatReport(
            supportedEvents = supported,
            unsupportedEvents = unsupported,
            fullyCompatible = unsupported.isEmpty() && supported.isNotEmpty(),
        )
    }

    /** 兼容性报告（详情页数据源）。 */
    data class HookCompatReport(
        val supportedEvents: List<String>,
        val unsupportedEvents: List<String>,
        /** 全部声明事件都支持（且有声明）—— 才标「兼容 OpenCode hooks」。 */
        val fullyCompatible: Boolean,
    )
}
