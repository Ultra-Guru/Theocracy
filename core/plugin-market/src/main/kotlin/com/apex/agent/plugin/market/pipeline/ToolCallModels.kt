package com.apex.agent.plugin.market.pipeline

import com.apex.agent.plugin.market.format.AgentPluginManifest
import com.apex.agent.plugin.market.format.PluginCapability

/**
 * 管线请求/结果模型（Spec §8：Capability → Policy → Approval → Execute → Audit）。
 */

/** 一次工具调用的请求（Agent 侧发起）。 */
data class ToolCallRequest(
    /** 发起插件 id（归属审计与能力判定）。 */
    val pluginId: String,
    /** Agent 可见工具名。 */
    val toolName: String,
    /** 调用参数（扁平字符串键值；复杂结构以 JSON 字符串为值）。 */
    val args: Map<String, String> = emptyMap(),
)

/** 管线五段的可判定上下文（由宿主在调用前组装）。 */
data class ToolCallContext(
    val request: ToolCallRequest,
    /** 插件 manifest（能力判定数据源 —— capability 必须已声明）。 */
    val manifest: AgentPluginManifest,
    /** 该调用归属的能力声明（tool → capability 映射由宿主路由层解析）。 */
    val capability: PluginCapability,
) {
    val risk: RiskLevel
        get() = RiskLevel.of(capability.risk) ?: RiskLevel.R3  // 未知档位按最高档保守
}

/**
 * 管线输出 —— 三态：待确认 / 执行结果 / 拒绝。
 *
 * 「待确认」不是错误：R2/R3 调用在用户未确认时返回 [AwaitingApproval]，
 * 宿主把 [ApprovalRequest] 渲染成确认卡，用户选择后**带 confirmation
 * 重新提交**同一请求走完管线。
 */
sealed class ToolCallResult {
    /** 执行完成（结果负载 + 审计已落）。 */
    data class Executed(val output: String, val durationMs: Long) : ToolCallResult()

    /** 待用户确认（R2/R3 且未带有效 confirmation）。 */
    data class AwaitingApproval(val approval: ApprovalRequest) : ToolCallResult()

    /** 拒绝（reason 稳定码 + 用户可读说明；审计已落）。 */
    data class Denied(val reason: String, val detail: String) : ToolCallResult()
}

/** R2+ 确认卡模型（Spec §7.1/§8.2：工具名 + 参数摘要 + 影响范围 + 来源插件）。 */
data class ApprovalRequest(
    /** 确认 id（防重放：confirmation 必须与本 id 配对才有效）。 */
    val confirmationId: String,
    val pluginId: String,
    val toolName: String,
    val capabilityId: String,
    val risk: RiskLevel,
    /** 参数摘要（已脱敏 —— 确认卡展示用）。 */
    val argsSummary: Map<String, String>,
    /** 用户可读影响范围（capability.description）。 */
    val impact: String,
    /** R3 强提示标记（UI 渲染红色警示）。 */
    val strongWarning: Boolean,
) {
    init {
        require(confirmationId.isNotEmpty()) { "confirmationId 不能为空" }
    }
}

/** 用户对确认卡的回应（重放防线：一次性，用后即焚）。 */
data class ApprovalConfirmation(
    val confirmationId: String,
    /** 用户选择：批准本次 / 批准本会话（仅 R2）/ 拒绝。 */
    val decision: Decision,
) {
    enum class Decision { APPROVE_ONCE, APPROVE_SESSION, DENY }
}
