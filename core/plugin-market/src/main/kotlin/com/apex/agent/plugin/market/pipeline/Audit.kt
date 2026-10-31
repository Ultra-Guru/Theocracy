package com.apex.agent.plugin.market.pipeline

import com.apex.agent.core.logging.AppLogger
import com.apex.agent.core.logging.LogCategory

/**
 * 审计子系统（Spec §5.3/§8.2）：脱敏审计记录 + Sink。
 *
 * 每次工具调用（含拒绝路径）必须落一条 [AuditRecord]：调用时间、
 * 插件 id、tool name、参数摘要（脱敏）、结果状态、用户确认记录。
 */

/** 审计记录（不可变快照；字段按 Spec §5.3 审计清单）。 */
data class AuditRecord(
    val timestampMs: Long,
    val pluginId: String,
    val toolName: String,
    val capabilityId: String,
    val risk: String,
    /** 结果状态（Executed / Denied / AwaitingApproval / ApprovalConsumed）。 */
    val outcome: String,
    /** 参数摘要（已脱敏 —— [SensitiveParamRedactor.redact]）。 */
    val argsSummary: Map<String, String>,
    /** 用户确认记录（本次调用的 confirmationId；未涉确认为 null）。 */
    val confirmationId: String?,
    /** 执行耗时（仅 Executed 有值）。 */
    val durationMs: Long?,
)

/** 审计落盘接口（持久化接线后续 PR；内存实现供测试与会话内回看）。 */
interface AuditSink {
    fun record(entry: AuditRecord)
}

/** 内存 Sink（测试 + 会话内审计回看）。 */
class InMemoryAuditSink : AuditSink {
    private val entries = mutableListOf<AuditRecord>()
    override fun record(entry: AuditRecord) {
        synchronized(entries) { entries += entry }
    }

    fun all(): List<AuditRecord> = synchronized(entries) { entries.toList() }
}

/**
 * 敏感参数脱敏（Spec §8.2 审计纪律：参数摘要脱敏后入审计）。
 *
 * 两道防线：
 * 1. **键名启发**：token/key/secret/password/authorization/auth（大小写
 *    不敏感，含驼峰）命中的参数值整体遮蔽；
 * 2. **值形态启发**：长得像凭证的值（JWT 三段式 / 40+ 位 hex /
 *    Basic/Bearer 前缀）即使键名无害也遮蔽 —— 防攻击者把 token 塞进
 *    `query` 这类无害键。
 *
 * 保留语义密度：脱敏值的长度保留一位数字（`<redacted:38>`），审计可
 * 判断「这有个长凭证」而不见内容。
 */
object SensitiveParamRedactor {

    private val SENSITIVE_KEY = Regex("(?i)(token|secret|password|authorization|^auth$|apikey|api_key|credential|sessionid|session_id)")

    /** JWT 形态（三段 base64url）。 */
    private val JWT_LIKE = Regex("^[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}$")

    /** 长 hex（SHA-256 / API key 形态）。 */
    private val LONG_HEX = Regex("^[0-9a-fA-F]{40,}$")

    /** Bearer / Basic 头形态。 */
    private val CREDENTIAL_PREFIX = Regex("(?i)^(bearer|basic|token)\\s+")

    fun redact(args: Map<String, String>): Map<String, String> =
        args.mapValues { (key, value) ->
            when {
                SENSITIVE_KEY.containsMatchIn(key) -> mask(value)
                looksLikeCredential(value) -> mask(value)
                else -> value.take(MAX_VALUE_CHARS)  // 非敏感值也截断（审计体积防线）
            }
        }

    private fun mask(value: String): String = "<redacted:${value.length}>"

    private fun looksLikeCredential(value: String): Boolean =
        JWT_LIKE.matches(value) || LONG_HEX.matches(value) || CREDENTIAL_PREFIX.containsMatchIn(value)

    /** 非敏感值截断上限（审计体积防线）。 */
    const val MAX_VALUE_CHARS = 200

    /**
     * 参数摘要（确认卡与审计共用 —— 双出口同脱敏，防确认卡泄密审计不泄
     * 的不一致窗口）。
     */
    fun summaryOf(args: Map<String, String>): Map<String, String> = redact(args)
}
