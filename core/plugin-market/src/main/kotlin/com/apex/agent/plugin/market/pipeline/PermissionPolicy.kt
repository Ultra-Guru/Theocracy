package com.apex.agent.plugin.market.pipeline

import com.apex.agent.plugin.market.format.PluginCapability
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 权限授权模型（Spec §8.2：安装时 vs 运行时授权）。
 *
 * ## 授权存储三档
 *
 * - **安装时授权**（R0/R1 由安装向导写入；R2+ 默认不授予 —— Spec 硬规）；
 * - **运行时授权**：R1 首次调用请求后记录（之后自动）；
 * - **会话授权**：R2 的「本会话不再问」（会话结束整体失效）；
 *   R3 **永不**会话化（逐次确认是底线）。
 *
 * ## 撤销
 *
 * 用户在「我的插件 → 权限管理」可撤销任何已授予权限（[revoke]）——
 * 撤销即时生效于后续调用（管线每次判定都过 [decide]）。
 */
class PermissionPolicy(
    /** 确认 id 生成器（测试注入确定性序列；生产用随机）。 */
    private val confirmationIdGen: () -> String = { "conf-${System.nanoTime()}" },
) {

    /** 能力授权键（pluginId + capabilityId 唯一定位）。 */
    private data class CapabilityKey(val pluginId: String, val capabilityId: String)

    private val installedGrants = ConcurrentHashMap<CapabilityKey, RiskLevel>()
    private val runtimeGrants = ConcurrentHashMap<CapabilityKey, RiskLevel>()
    private val sessionGrants = ConcurrentHashMap<CapabilityKey, RiskLevel>()

    /** 待确认请求登记（confirmationId → ApprovalRequest；一次性，用后即焚）。 */
    private val pendingConfirmations = ConcurrentHashMap<String, ApprovalRequest>()

    /** 会话 id（隔离多会话的会话授权；换会话即全清 sessionGrants）。 */
    private val sessionId = AtomicLong(0)

    /** 安装时授权写入（安装向导调用；R2+ 的授予是用户显式选择）。 */
    fun grantAtInstall(pluginId: String, capability: PluginCapability) {
        installedGrants[CapabilityKey(pluginId, capability.id)] =
            RiskLevel.of(capability.risk) ?: RiskLevel.R3
    }

    /** R1 首次批准后记录（运行时授权）。 */
    fun grantRuntime(pluginId: String, capabilityId: String, risk: RiskLevel) {
        runtimeGrants[CapabilityKey(pluginId, capabilityId)] = risk
    }

    /** R2 会话批量批准（仅 [RiskLevel.allowsSessionGrant] 的档位接受）。 */
    fun grantSession(pluginId: String, capabilityId: String, risk: RiskLevel) {
        if (!risk.allowsSessionGrant) return  // R3 会话化请求静默拒绝（不该发生，防御）
        sessionGrants[CapabilityKey(pluginId, capabilityId)] = risk
    }

    /** 撤销任何档位的授权（用户权限管理入口）。 */
    fun revoke(pluginId: String, capabilityId: String) {
        val key = CapabilityKey(pluginId, capabilityId)
        installedGrants.remove(key)
        runtimeGrants.remove(key)
        sessionGrants.remove(key)
    }

    /** 新会话：会话授权整体失效（安装时 + R1 运行时授权保留）。 */
    fun rotateSession() {
        sessionGrants.clear()
        pendingConfirmations.clear()
        sessionId.incrementAndGet()
    }

    /**
     * 登记待确认请求（管线 AwaitingApproval 前调用）→ 确认卡。
     */
    fun registerApproval(approval: ApprovalRequest): ApprovalRequest {
        pendingConfirmations[approval.confirmationId] = approval
        return approval
    }

    /**
     * 消费确认（一次性）：校验 id 配对 + 决策落授权存储。
     * 返回 null = id 未知或已被消费（重放/伪造 → 上层按拒绝处理）。
     */
    fun consumeConfirmation(confirmation: ApprovalConfirmation): ApprovalRequest? {
        val approval = pendingConfirmations.remove(confirmation.confirmationId) ?: return null
        when (confirmation.decision) {
            ApprovalConfirmation.Decision.APPROVE_ONCE -> Unit  // 仅本次（管线本次放行）
            ApprovalConfirmation.Decision.APPROVE_SESSION -> grantSession(
                approval.pluginId, approval.capabilityId, approval.risk,
            )
            ApprovalConfirmation.Decision.DENY -> Unit  // 拒绝：不落授权，管线按 Denied 处理
        }
        return approval
    }

    /**
     * 审批判定（管线 Policy 段）。
     *
     * 判定顺序（安全语义从宽到严）：
     * 1. R0 → Auto（无需任何授权）；
     * 2. 已有授权（安装时/运行时/会话）且档位匹配 → Granted；
     *    - 会话授权只认 R2（R3 在 grantSession 已挡）；
     * 3. R1 未授权 → NeedsApproval（首次授权请求 —— 卡文案与 R2 不同：批准后免问）；
     * 4. R2/R3 无授权 → NeedsApproval（每次/逐次确认）。
     */
    fun decide(ctx: ToolCallContext): PolicyDecision {
        val risk = ctx.risk
        if (risk == RiskLevel.R0) return PolicyDecision.Auto
        val key = CapabilityKey(ctx.request.pluginId, ctx.capability.id)
        val hasGrant = installedGrants.containsKey(key) || runtimeGrants.containsKey(key) ||
            (risk.allowsSessionGrant && sessionGrants.containsKey(key))
        if (hasGrant) return PolicyDecision.Granted
        return PolicyDecision.NeedsApproval(risk)
    }

    /** Policy 段输出三态。 */
    sealed class PolicyDecision {
        object Auto : PolicyDecision()
        object Granted : PolicyDecision()
        data class NeedsApproval(val risk: RiskLevel) : PolicyDecision()
    }
}
