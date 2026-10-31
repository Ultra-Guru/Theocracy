package com.apex.agent.platform.terminal.policy

import com.apex.agent.platform.terminal.io.InputOwner

/**
 * TerminalPolicy implementation backed by [CommandPolicy] (Spec PR #51 §9/§10).
 *
 * Replaces the fragile Regex denylist with proper command parsing + allowlist/mode.
 * Policy is consulted BEFORE PTY write (Spec §7) — DENY commands never reach the shell.
 *
 * Agent CANNOT modify this policy (Spec §11). It's controlled by App/System/User via Hilt.
 *
 * ## 动态策略源（设置页接线）
 *
 * [dynamicPolicy] 在每次 check 时被调用 —— 返回非 null 的 [CommandPolicy] 覆盖
 * 构造期默认值。App 层用它把 SharedPreferences 里的用户黑白名单（终端设置页）
 * 接入策略层：用户在设置页增删名单**立即生效**于下一次 LINE 写入检查，无需重建
 * 单例。返回 null 时回退到构造期 [commandPolicy]（默认行为，测试可用）。
 */
class TerminalPolicyImpl(
    private val privilege: PrivilegeLevel = PrivilegeLevel.NORMAL,
    private val commandPolicy: CommandPolicy = CommandPolicy(),
    private val dynamicPolicy: (() -> CommandPolicy?)? = null,
    /**
     * T-RISK（terminal 十连 PR 4/10）：四级风险分类器。CRITICAL 级在 writePty
     * 前硬拒 —— 纵深防御：MCP 门控（PR 5/10）之外的第二道独立边界，被绕过
     * 时仍兕底。默认构造零配置（测试与既有调用点不变）。注：denylist 拒绝
     * 的命令保留原拒绝理由，不重复分类。
     */
    private val riskClassifier: RiskClassifier = RiskClassifier(),
) : TerminalPolicy {

    override fun check(request: InputRequest): Decision {
        val cmd = request.command ?: return Decision.Allow
        val effective = dynamicPolicy?.invoke() ?: commandPolicy
        // T85 → P0 修复（用户反馈"Agent 不会主动调用命令、终端用不了"）：
        // 全部 owner 统一走**分段检查**（逐段黑名单 + 包装器 deep-scan + 引号透明）。
        //
        // 旧行为：AGENT 的 LINE 命令执行走保守 parse 路径 —— 含 `&&`/`|`/`;`/`$(`/
        // 引号首 token 的命令一律 complex → DENY。Agent 的直觉命令（`cd /workspace
        // && ls`、`pip install x && python y.py`、`echo "a" | grep b`）每次被拒，
        // 且 JobManager 把 PermissionDenied 误报为 WriteFailed —— 模型无法分辨
        // 「会话坏了」还是「命令被拦」，反复撞墙后放弃终端 → 用户观感"终端用不了"。
        //
        // 安全性不降级：checkSegments 对每一段做与保守路径等强的检查 ——
        //  - 黑名单逐段拦截（`echo hi && rm -rf /` → 段 rm 命中 → DENY）；
        //  - 引号/反斜杠去壳后精确匹配（`"rm"` / `r\m` → rm → DENY）；
        //  - shell 包装器段 deep-scan 全 token（`bash -c "shutdown"` → DENY）；
        //  - 前导环境变量赋值剥离（`FOO=1 rm …` → DENY）；
        //  - 家族变体匹配（`mkfs.ext4` → mkfs → DENY）。
        // 已知极限（变量间接 / 运行时拼接）由防御纵深兜底：PRoot 沙箱、
        // CommandPermissionGate 确认门、RiskAwareToolGate —— 与 USER/SYSTEM
        // 路径此前已承担的残余风险一致（T85 KDoc 存档）。
        val decision = effective.checkSegments(cmd)
        if (decision == CommandPolicyDecision.ALLOW) {
            // T-RISK：CRITICAL 硬拒（不可逆灾难级：写块设备/提权/格式化/批量删根级
            // 路径）。确定性词法判定，不依赖模型； denylist 未覆盖的形态（rm -rf /
            // 不在内置名单）在此兑底。
            val risk = riskClassifier.classify(cmd)
            if (risk.level == RiskLevel.CRITICAL) {
                return Decision.Deny(
                    reason = "RISK_CRITICAL: ${risk.reason} — hard-blocked before PTY write"
                )
            }
        }
        return mapDecision(decision, CommandParser.parse(cmd))
    }

    override fun capabilities(): TerminalCapability = TerminalCapability.forLevel(privilege)

    companion object {
        /**
         * Map a [CommandPolicyDecision] to the final runtime [Decision] (Spec §10).
         *
         * v1 fail-safe: [CommandPolicy.check] only emits ALLOW/DENY. If a future policy layer
         * ever emits [CommandPolicyDecision.REQUIRE_CONFIRMATION] while no Confirmation UI
         * exists, it MUST be DENIED — never auto-allowed. Silently allowing a command that
         * requires confirmation would let destructive commands through the policy gate.
         */
        internal fun mapDecision(decision: CommandPolicyDecision, parsed: ParsedCommand): Decision {
            val exe = parsed.executable ?: "complex"
            return when (decision) {
                CommandPolicyDecision.ALLOW -> Decision.Allow
                CommandPolicyDecision.DENY -> Decision.Deny(
                    reason = "COMMAND_POLICY_DENIED: command '$exe' blocked by policy"
                )
                CommandPolicyDecision.REQUIRE_CONFIRMATION -> Decision.Deny(
                    reason = "COMMAND_POLICY_CONFIRMATION_REQUIRED: command '$exe' requires confirmation; " +
                        "no confirmation UI available in v1 — denied"
                )
            }
        }
    }
}
