package com.apex.agent.terminalmcp.security

import com.apex.agent.platform.terminal.policy.RiskAssessment
import com.apex.agent.platform.terminal.policy.RiskClassifier
import com.apex.agent.platform.terminal.policy.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * T-GATE — 写入前安全门控（terminal 十连 PR 5/10）。
 *
 * ## 拦截点语义
 * 命令一旦进入 PTY 就无法拦截 —— 门控必须在写入前（nl2sh 的
 * Security → Confirmation → Execution 链式设计）。本门控消费 PR 4/10 的
 * [RiskClassifier]（模型只提案，本地程序裁定）：
 *
 * | 级别 | 决策 |
 * |---|---|
 * | READ_ONLY / MUTATING | [GateDecision.Allowed]（自动放行；事件流分级记录） |
 * | DANGEROUS | [GateDecision.RequireConfirmation] —— 人工确认后才可执行 |
 * | CRITICAL | [GateDecision.Blocked] —— 拒绝执行 + 醒目告警，**不可绕过** |
 *
 * ## 一次性批准（one-shot approval）
 * Dangerous 命令的确认流：`evaluate → RequireConfirmation(confirmId)` 返回
 * Agent（命令**未执行**）→ 人侧 [resolve]（准许/拒绝）→ Agent 重试同一命令
 * → 命中未过期批准票 → [Allowed] 并**消费**该票（再执行同命令需再确认）。
 * 批准票 TTL [APPROVAL_TTL_MS]（120s，nl2sh 范式）—— Agent 重试窗口足够、
 * 长期重放不可能。
 *
 * ## 人侧确认入口
 * [pendingFlow]（StateFlow）驱动 UI（PR 9/10 控制中心的待确认列表 + 通知
 * 动作）。本 PR 阶段确认可经 [resolve] API（测试与程序化入口），PR 9/10
 * 接 UI —— Dangerous 命令在两 PR 之间处于「返回 confirmation_required、
 * 等待人侧裁决」状态，绝无静默放行。
 *
 * 时钟注入（[clock]）保证 TTL 语义可在 JVM 单测中无 sleep 验证。
 */
class SecurityGate(
    private val classifier: RiskClassifier = RiskClassifier(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    // ─── 决策模型 ───

    sealed class GateDecision {
        /** 放行（含分类依据 —— 事件流分级记录用）。 */
        data class Allowed(val assessment: RiskAssessment) : GateDecision()

        /** Dangerous：等待人工确认。命令**未执行**；批准后重试放行（一次性）。 */
        data class RequireConfirmation(
            val confirmId: String,
            val assessment: RiskAssessment,
            val expiresAtMs: Long,
        ) : GateDecision()

        /** Critical：拒绝执行，不可确认绕过。 */
        data class Blocked(val assessment: RiskAssessment) : GateDecision()
    }

    /** 一张待确认单（人侧 UI 的数据模型）。 */
    data class PendingConfirmation(
        val confirmId: String,
        val command: String,
        val sessionId: Long?,
        val assessment: RiskAssessment,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    ) {
        val commandHash: String get() = hashCommand(command)
    }

    /** 一张一次性批准票（resolve(approved=true) 后生成，evaluate 命中即消费）。 */
    private data class ApprovalTicket(
        val commandHash: String,
        val expiresAtMs: Long,
    )

    // ─── 状态 ───

    private val mutex = Mutex()
    private val pendingById = ConcurrentHashMap<String, PendingConfirmation>()
    private val approvals = ConcurrentHashMap<String, ApprovalTicket>() // hash → ticket

    private val _pendingFlow = MutableStateFlow<List<PendingConfirmation>>(emptyList())
    val pendingFlow: StateFlow<List<PendingConfirmation>> = _pendingFlow

    // ─── 门控入口 ───

    /**
     * 写入前评估。线程安全；过期单/票惰性清理。
     */
    suspend fun evaluate(command: String, sessionId: Long?): GateDecision = mutex.withLock {
        val now = clock()
        purgeExpired(now)
        val assessment = classifier.classify(command)
        when (assessment.level) {
            RiskLevel.READ_ONLY, RiskLevel.MUTATING -> GateDecision.Allowed(assessment)
            RiskLevel.DANGEROUS -> {
                val hash = hashCommand(command)
                // 一次性批准票：同命令 hash 未过期 → 放行并消费
                val ticket = approvals.remove(hash)
                if (ticket != null && ticket.expiresAtMs > now) {
                    GateDecision.Allowed(assessment)
                } else {
                    // 未决单去重：同命令已有未过期单 → 复用 confirmId（Agent 重试不刷屏）
                    val existing = pendingById.values.firstOrNull {
                        it.commandHash == hash && it.expiresAtMs > now
                    }
                    if (existing != null) {
                        GateDecision.RequireConfirmation(existing.confirmId, assessment, existing.expiresAtMs)
                    } else {
                        val confirmId = UUID.randomUUID().toString().substring(0, 8)
                        val pending = PendingConfirmation(
                            confirmId = confirmId,
                            command = command,
                            sessionId = sessionId,
                            assessment = assessment,
                            createdAtMs = now,
                            expiresAtMs = now + PENDING_TTL_MS,
                        )
                        pendingById[confirmId] = pending
                        publishPending()
                        GateDecision.RequireConfirmation(confirmId, assessment, pending.expiresAtMs)
                    }
                }
            }
            RiskLevel.CRITICAL -> GateDecision.Blocked(assessment)
        }
    }

    /**
     * 人工裁决。批准 → 生成一次性批准票（TTL [APPROVAL_TTL_MS]）；拒绝 → 仅移除待确认单。
     * @return 单据存在且未过期时 true（裁决生效）。
     */
    suspend fun resolve(confirmId: String, approved: Boolean): Boolean = mutex.withLock {
        val now = clock()
        purgeExpired(now)
        val pending = pendingById.remove(confirmId) ?: return false
        if (approved) {
            approvals[pending.commandHash] = ApprovalTicket(
                commandHash = pending.commandHash,
                expiresAtMs = now + APPROVAL_TTL_MS,
            )
        }
        publishPending()
        true
    }

    /** 待确认单快照（gate_status 工具 / UI）。 */
    fun pendingList(): List<PendingConfirmation> = pendingById.values
        .filter { it.expiresAtMs > clock() }
        .sortedBy { it.createdAtMs }

    /** 单据查询；不存在/已过期 → null。 */
    fun findPending(confirmId: String): PendingConfirmation? =
        pendingById[confirmId]?.takeIf { it.expiresAtMs > clock() }

    /** 当前生效的一次性批准票数（诊断/UI 角标用）。 */
    fun activeApprovalCount(): Int = approvals.count { it.value.expiresAtMs > clock() }

    // ─── 内部 ───

    private fun purgeExpired(now: Long) {
        val expiredPending = pendingById.entries.filter { it.value.expiresAtMs <= now }
        if (expiredPending.isNotEmpty()) {
            expiredPending.forEach { pendingById.remove(it.key) }
            publishPending()
        }
        approvals.entries.removeIf { it.value.expiresAtMs <= now }
    }

    private fun publishPending() {
        _pendingFlow.value = pendingById.values.sortedBy { it.createdAtMs }.toList()
    }

    companion object {
        /** 待确认单 TTL：5 分钟（人侧看到、决定的时间预算）。 */
        const val PENDING_TTL_MS = 5 * 60_000L

        /** 一次性批准票 TTL：120s（Agent 重试窗口；nl2sh 范式）。 */
        const val APPROVAL_TTL_MS = 120_000L

        /** 命令指纹（批准票的匹配键）：SHA-256 前 16 hex。 */
        fun hashCommand(command: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(command.toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }
    }
}
