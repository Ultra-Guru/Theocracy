package com.apex.agent.plugin.market.pipeline

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * 统一工具执行管线（Spec §2 架构核心 + §8 安全模型）：
 *
 * ```
 * Schema 校验 → Capability 判定 → Policy 审批 → Execute → Audit
 * ```
 *
 * ## 设计纪律
 *
 * - **每个拒绝路径都审计**（Denied/Rejected/AwaitingApproval 全落记录）——
 *   审计是调用全生命周期记录，不是成功执行的副产品；
 * - **防御式执行**：executor 异常折叠为 Denied（EXECUTION_FAILED），
 *   不向上抛 —— 与仓库防御式 IO 纪律一致；
 * - **Android 权限 ≠ Agent 授权**：本层零 Android 依赖，判定只看
 *   capability 声明 + [PermissionPolicy] 授权状态；
 * - **确认流**：R2/R3 未确认 → [ToolCallResult.AwaitingApproval]（非异常）；
 *   宿主渲染确认卡，用户回应后带 [ApprovalConfirmation] 重放请求。
 */
class ToolCallPipeline(
    private val policy: PermissionPolicy,
    private val executor: ToolExecutor,
    private val auditSink: AuditSink,
    /** 虚拟钟注入（审计时间戳）。 */
    private val clock: () -> Long = System::currentTimeMillis,
    /** 确认 id 生成（测试注入确定性）。 */
    private val confirmationIdGen: () -> String = { "conf-${System.nanoTime()}" },
) {

    /** 执行器接口（宿主注入真实实现 —— MCP 调用 / AppFunction 调用 / skill 脚本）。 */
    fun interface ToolExecutor {
        /** @return 输出文本；失败抛异常（管线折叠）。 */
        fun execute(ctx: ToolCallContext): String
    }

    /**
     * 管线入口。首次调用不带 confirmation（R2/R3 → AwaitingApproval）；
     * 用户确认后带 confirmation 重放同一请求。
     */
    fun call(ctx: ToolCallContext, confirmation: ApprovalConfirmation? = null): ToolCallResult {
        val args = ctx.request.args

        // ── 1. Schema 校验（结构层：键合法 / 无空键）────────────────
        if (args.keys.any { it.isBlank() }) {
            return denied(ctx, "SCHEMA_INVALID", "参数含空键", confirmation?.confirmationId)
                .also { audit(ctx, "Rejected", args, confirmation?.confirmationId, null) }
        }

        // ── 2. Capability 判定（capability 必须在 manifest 声明）────
        if (ctx.capability !in ctx.manifest.capabilities) {
            return denied(ctx, "CAPABILITY_UNDECLARED",
                "工具调用的 capability 未在插件 manifest 声明（越权调用）",
                confirmation?.confirmationId)
                .also { audit(ctx, "Denied", args, confirmation?.confirmationId, null) }
        }

        // ── 3. Policy 审批 ──────────────────────────────────────────
        val risk = ctx.risk
        val decision = policy.decide(ctx)

        val approvalId: String? = when (decision) {
            is PermissionPolicy.PolicyDecision.Auto, is PermissionPolicy.PolicyDecision.Granted -> null
            is PermissionPolicy.PolicyDecision.NeedsApproval -> {
                if (confirmation == null) {
                    // 未确认：发确认卡（R1 首次授权卡 / R2+ 确认卡）
                    val request = policy.registerApproval(
                        ApprovalRequest(
                            confirmationId = confirmationIdGen(),
                            pluginId = ctx.request.pluginId,
                            toolName = ctx.request.toolName,
                            capabilityId = ctx.capability.id,
                            risk = risk,
                            argsSummary = SensitiveParamRedactor.summaryOf(args),
                            impact = ctx.capability.description,
                            strongWarning = risk == RiskLevel.R3,
                        )
                    )
                    audit(ctx, "AwaitingApproval", args, request.confirmationId, null)
                    return ToolCallResult.AwaitingApproval(request)
                }
                // 带确认重放：消费（一次性）—— 未知/重放 id 视为拒绝
                val consumed = policy.consumeConfirmation(confirmation)
                    ?: return denied(ctx, "CONFIRMATION_INVALID",
                        "确认 id 无效或已被消费（重放拒绝）", null)
                        .also { audit(ctx, "Denied", args, null, null) }
                when (confirmation.decision) {
                    ApprovalConfirmation.Decision.DENY ->
                        return denied(ctx, "USER_DENIED", "用户拒绝了本次调用", consumed.confirmationId)
                            .also { audit(ctx, "Denied", args, consumed.confirmationId, null) }
                    ApprovalConfirmation.Decision.APPROVE_ONCE -> {
                        // R1 首次批准 → 记运行时授权（之后自动）
                        if (risk == RiskLevel.R1) policy.grantRuntime(
                            ctx.request.pluginId, ctx.capability.id, risk,
                        )
                        consumed.confirmationId
                    }
                    ApprovalConfirmation.Decision.APPROVE_SESSION -> consumed.confirmationId
                }
            }
        }

        // ── 4. Execute（异常折叠）──────────────────────────────────
        val start = clock()
        val executed: String = try {
            executor.execute(ctx)
        } catch (e: Exception) {
            AppLogger.instance.warn(
                LogCategory.PLUGIN, "ToolCallPipeline",
                "工具执行异常（折叠为 Denied）: ${ctx.request.toolName}: ${e.message}"
            )
            return denied(ctx, "EXECUTION_FAILED",
                "执行失败: ${e.message ?: e.javaClass.simpleName}", approvalId)
                .also { audit(ctx, "Denied", args, approvalId, clock() - start) }
        }

        // ── 5. Audit（Executed）────────────────────────────────────
        val duration = clock() - start
        audit(ctx, "Executed", args, approvalId, duration)
        return ToolCallResult.Executed(executed, duration)
    }

    // ── 内部 ───────────────────────────────────────────────────────

    private fun denied(ctx: ToolCallContext, reason: String, detail: String, confId: String?) =
        ToolCallResult.Denied(reason, detail)

    private fun audit(
        ctx: ToolCallContext,
        outcome: String,
        args: Map<String, String>,
        confirmationId: String?,
        durationMs: Long?,
    ) {
        auditSink.record(
            AuditRecord(
                timestampMs = clock(),
                pluginId = ctx.request.pluginId,
                toolName = ctx.request.toolName,
                capabilityId = ctx.capability.id,
                risk = ctx.risk.code,
                outcome = outcome,
                argsSummary = SensitiveParamRedactor.redact(args),
                confirmationId = confirmationId,
                durationMs = durationMs,
            )
        )
    }
}
