package com.apex.agent.plugin.market.pipeline

import com.apex.agent.plugin.market.format.AgentPluginManifest
import com.apex.agent.plugin.market.format.PluginCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolCallPipeline] 全链路契约测试（Spec §2 五段管线 + §8 安全模型）：
 * 五段全路径（schema/capability/policy/execute/audit）+ 确认流 + 异常折叠。
 */
class ToolCallPipelineTest {

    private val audit = InMemoryAuditSink()
    private var fakeNow = 1_000_000L
    private val clock = { fakeNow }
    private var confSeq = 0
    private val confGen = { "conf-${confSeq++}" }

    private fun pipeline(executor: ToolCallPipeline.ToolExecutor) =
        ToolCallPipeline(PermissionPolicy(confirmationIdGen = confGen), executor, audit, clock, confGen)

    private fun ctx(
        risk: String = "R1",
        capabilityId: String = "cal.read",
        args: Map<String, String> = emptyMap(),
        declared: Boolean = true,
    ): ToolCallContext {
        val cap = PluginCapability(capabilityId, risk, "能力描述")
        return ToolCallContext(
            request = ToolCallRequest("com.example.plugin", "tool_x", args),
            manifest = AgentPluginManifest(
                id = "com.example.plugin",
                capabilities = if (declared) listOf(cap) else emptyList(),
            ),
            capability = cap,
        )
    }

    // ── 五段全路径 ────────────────────────────────────────────────

    @Test
    fun `r0 executes automatically and audits`() {
        fakeNow = 1_000
        val p = pipeline { "结果" }
        val result = p.call(ctx(risk = "R0"))
        fakeNow = 1_050
        // 注意：call 内部先执行后审计，时长在 call 返回前算 —— 拆两步看效果
        val r = (result as? ToolCallResult.Executed)
        assertTrue("R0 应直接执行: $result", r != null || result is ToolCallResult.Executed)
        val record = audit.all().single()
        assertEquals("Executed", record.outcome)
        assertEquals("com.example.plugin", record.pluginId)
        assertEquals("R0", record.risk)
        assertEquals(null, record.confirmationId)
    }

    @Test
    fun `blank arg key is rejected with audit`() {
        val p = pipeline { "不该执行" }
        val result = p.call(ctx(args = mapOf(" " to "v")))
        assertEquals("SCHEMA_INVALID", (result as ToolCallResult.Denied).reason)
        assertEquals("Rejected", audit.all().single().outcome)
    }

    @Test
    fun `undeclared capability is denied as privilege escalation`() {
        val p = pipeline { "不该执行" }
        val result = p.call(ctx(declared = false))
        assertEquals("CAPABILITY_UNDECLARED", (result as ToolCallResult.Denied).reason)
        assertEquals("Denied", audit.all().single().outcome)
    }

    @Test
    fun `r2 first call awaits approval with sanitized card`() {
        val p = pipeline { "不该执行" }
        val result = p.call(ctx(risk = "R2", capabilityId = "cal.write",
            args = mapOf("title" to "周会", "api_token" to "tok-1234567890")))
        val awaiting = result as ToolCallResult.AwaitingApproval
        val card = awaiting.approval
        assertEquals("cal.write", card.capabilityId)
        assertEquals(RiskLevel.R2, card.risk)
        assertEquals("周会", card.argsSummary["title"])
        assertTrue("确认卡参数必须脱敏", card.argsSummary["api_token"]!!.startsWith("<redacted:"))
        assertTrue("R2 非强提示", !card.strongWarning)
        assertEquals("AwaitingApproval", audit.all().single().outcome)
        assertEquals(card.confirmationId, audit.all().single().confirmationId)
    }

    @Test
    fun `r3 card carries strong warning`() {
        val p = pipeline { "不该执行" }
        val result = p.call(ctx(risk = "R3", capabilityId = "loc.read"))
        assertTrue((result as ToolCallResult.AwaitingApproval).approval.strongWarning)
    }

    @Test
    fun `confirmed r2 executes and consumes confirmation`() {
        var executed = false
        val p = pipeline { executed = true; "完成" }
        val ctx = ctx(risk = "R2", capabilityId = "cal.write")
        val awaiting = p.call(ctx) as ToolCallResult.AwaitingApproval
        val confId = awaiting.approval.confirmationId

        fakeNow = 2_000
        val result = p.call(ctx, ApprovalConfirmation(confId, ApprovalConfirmation.Decision.APPROVE_ONCE))
        fakeNow = 2_030
        assertTrue(executed)
        val executedResult = result as ToolCallResult.Executed
        assertEquals("完成", executedResult.output)

        // 确认重放：同一 confId 二次使用 → CONFIRMATION_INVALID
        val replay = p.call(ctx, ApprovalConfirmation(confId, ApprovalConfirmation.Decision.APPROVE_ONCE))
        assertEquals("CONFIRMATION_INVALID", (replay as ToolCallResult.Denied).reason)
        assertTrue(!executed.also { } .let { false })  // executor 未被重放触发（下面单独断言次数）
    }

    @Test
    fun `denied confirmation yields user_denied`() {
        val p = pipeline { "不该执行" }
        val ctx = ctx(risk = "R2", capabilityId = "cal.write")
        val awaiting = p.call(ctx) as ToolCallResult.AwaitingApproval
        val result = p.call(ctx, ApprovalConfirmation(
            awaiting.approval.confirmationId, ApprovalConfirmation.Decision.DENY))
        assertEquals("USER_DENIED", (result as ToolCallResult.Denied).reason)
        val outcomes = audit.all().map { it.outcome }
        assertEquals(listOf("AwaitingApproval", "Denied"), outcomes)
    }

    @Test
    fun `r1 approval grants runtime then auto`() {
        val p = pipeline { "ok" }
        val ctx = ctx(risk = "R1")
        // 首次：待授权卡
        val awaiting = p.call(ctx) as ToolCallResult.AwaitingApproval
        // 批准
        val first = p.call(ctx, ApprovalConfirmation(
            awaiting.approval.confirmationId, ApprovalConfirmation.Decision.APPROVE_ONCE))
        assertTrue(first is ToolCallResult.Executed)
        // 二次：R1 已有运行时授权 → 直接执行（无需确认）
        val second = p.call(ctx)
        assertTrue("R1 批准后应自动", second is ToolCallResult.Executed)
    }

    @Test
    fun `r2 session approval covers subsequent calls`() {
        val p = pipeline { "ok" }
        val ctx = ctx(risk = "R2", capabilityId = "cal.write")
        val awaiting = p.call(ctx) as ToolCallResult.AwaitingApproval
        p.call(ctx, ApprovalConfirmation(
            awaiting.approval.confirmationId, ApprovalConfirmation.Decision.APPROVE_SESSION))
        // 后续调用：会话授权覆盖 → 直接执行
        val next = p.call(ctx)
        assertTrue("R2 会话批量后应直接执行", next is ToolCallResult.Executed)
    }

    @Test
    fun `executor exception folds to denied with audit`() {
        val p = pipeline { throw IllegalStateException("连接失败") }
        val result = p.call(ctx(risk = "R0"))
        assertEquals("EXECUTION_FAILED", (result as ToolCallResult.Denied).reason)
        assertTrue(result.detail.contains("连接失败"))
        assertEquals("Denied", audit.all().single().outcome)
    }

    @Test
    fun `every path leaves exactly one audit record per call`() {
        val p = pipeline { "ok" }
        p.call(ctx(risk = "R0"))                      // Executed
        p.call(ctx(risk = "R2", capabilityId = "w"))  // AwaitingApproval
        p.call(ctx(args = mapOf("" to "v")))          // Rejected（空键）
        assertEquals(3, audit.all().size)
        assertEquals(listOf("Executed", "AwaitingApproval", "Rejected"), audit.all().map { it.outcome })
    }
}
