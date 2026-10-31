package com.apex.agent.plugin.market.pipeline

import com.apex.agent.plugin.market.format.PluginCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RiskLevel] + [PermissionPolicy] 契约测试（Spec §8.1/§8.2）：
 * 四档策略、三档授权存储、会话轮换、撤销、确认消费一次性。
 */
class PermissionPolicyTest {

    private fun cap(id: String = "cal.read", risk: String = "R1") =
        PluginCapability(id = id, risk = risk, description = "读日历")

    private fun ctxOf(
        pluginId: String = "com.example.plugin",
        risk: String = "R1",
        capabilityId: String = "cal.read",
    ) = ToolCallContext(
        request = ToolCallRequest(pluginId, "cal_read"),
        manifest = com.apex.agent.plugin.market.format.AgentPluginManifest(
            id = pluginId,
            capabilities = listOf(cap(capabilityId, risk)),
        ),
        capability = cap(capabilityId, risk),
    )

    // ── RiskLevel ─────────────────────────────────────────────────

    @Test
    fun `risk level domain and policies`() {
        assertEquals(4, RiskLevel.entries.size)
        assertEquals(listOf("R0", "R1", "R2", "R3"), RiskLevel.entries.map { it.code })
        assertFalse(RiskLevel.R0.requiresConfirmation)
        assertFalse(RiskLevel.R1.requiresConfirmation)
        assertTrue(RiskLevel.R2.requiresConfirmation)
        assertTrue(RiskLevel.R3.requiresConfirmation)
        assertTrue(RiskLevel.R2.allowsSessionGrant)
        assertFalse(RiskLevel.R3.allowsSessionGrant)  // R3 逐次是底线
        assertEquals(RiskLevel.R3, RiskLevel.of("R3"))
        assertNull(RiskLevel.of("R9"))
    }

    // ── 四档判定（Spec §8.1 表）──────────────────────────────────

    @Test
    fun `r0 always auto`() {
        val policy = PermissionPolicy()
        assertEquals(PermissionPolicy.PolicyDecision.Auto, policy.decide(ctxOf(risk = "R0")))
    }

    @Test
    fun `r1 needs approval first time then granted after runtime grant`() {
        val policy = PermissionPolicy()
        val ctx = ctxOf(risk = "R1")
        assertTrue(policy.decide(ctx) is PermissionPolicy.PolicyDecision.NeedsApproval)
        policy.grantRuntime(ctx.request.pluginId, ctx.capability.id, RiskLevel.R1)
        assertEquals(PermissionPolicy.PolicyDecision.Granted, policy.decide(ctx))
    }

    @Test
    fun `r2 session grant allows but r3 never`() {
        val policy = PermissionPolicy()
        val r2 = ctxOf(risk = "R2", capabilityId = "cal.write")
        val r3 = ctxOf(risk = "R3", capabilityId = "loc.read")

        policy.grantSession(r2.request.pluginId, r2.capability.id, RiskLevel.R2)
        assertEquals("R2 会话授权生效", PermissionPolicy.PolicyDecision.Granted, policy.decide(r2))

        policy.grantSession(r3.request.pluginId, r3.capability.id, RiskLevel.R3)  // 静默拒绝
        assertTrue("R3 会话化请求必须被挡", policy.decide(r3) is PermissionPolicy.PolicyDecision.NeedsApproval)
    }

    @Test
    fun `session rotation clears session grants but keeps runtime`() {
        val policy = PermissionPolicy()
        policy.grantSession("com.example.plugin", "cal.write", RiskLevel.R2)
        policy.grantRuntime("com.example.plugin", "cal.read", RiskLevel.R1)
        policy.rotateSession()
        assertTrue(policy.decide(ctxOf(risk = "R2", capabilityId = "cal.write"))
            is PermissionPolicy.PolicyDecision.NeedsApproval)
        assertEquals("R1 运行时授权跨会话保留",
            PermissionPolicy.PolicyDecision.Granted, policy.decide(ctxOf(risk = "R1")))
    }

    @Test
    fun `install grant satisfies r1 and r2`() {
        val policy = PermissionPolicy()
        policy.grantAtInstall("com.example.plugin", cap("cal.read", "R1"))
        policy.grantAtInstall("com.example.plugin", cap("cal.write", "R2"))
        assertEquals(PermissionPolicy.PolicyDecision.Granted, policy.decide(ctxOf(risk = "R1")))
        assertEquals(PermissionPolicy.PolicyDecision.Granted, policy.decide(ctxOf(risk = "R2", capabilityId = "cal.write")))
    }

    @Test
    fun `revoke removes all tiers`() {
        val policy = PermissionPolicy()
        policy.grantAtInstall("com.example.plugin", cap("cal.read", "R1"))
        policy.grantRuntime("com.example.plugin", "cal.read", RiskLevel.R1)
        policy.grantSession("com.example.plugin", "cal.read", RiskLevel.R2)
        policy.revoke("com.example.plugin", "cal.read")
        assertTrue(policy.decide(ctxOf(risk = "R1")) is PermissionPolicy.PolicyDecision.NeedsApproval)
    }

    // ── 确认消费（一次性防重放）──────────────────────────────────

    @Test
    fun `confirmation is single use`() {
        val policy = PermissionPolicy()
        val approval = ApprovalRequest(
            confirmationId = "conf-1", pluginId = "p", toolName = "t",
            capabilityId = "cal.write", risk = RiskLevel.R2,
            argsSummary = emptyMap(), impact = "写", strongWarning = false,
        )
        policy.registerApproval(approval)
        assertEquals(approval, policy.consumeConfirmation(
            ApprovalConfirmation("conf-1", ApprovalConfirmation.Decision.APPROVE_ONCE)))
        assertNull("二次消费（重放）必须失败", policy.consumeConfirmation(
            ApprovalConfirmation("conf-1", ApprovalConfirmation.Decision.APPROVE_ONCE)))
        assertNull("未知 id 失败", policy.consumeConfirmation(
            ApprovalConfirmation("conf-404", ApprovalConfirmation.Decision.APPROVE_ONCE)))
    }

    @Test
    fun `session approve consumes and grants session`() {
        val policy = PermissionPolicy()
        policy.registerApproval(ApprovalRequest(
            "conf-2", "p", "t", "cal.write", RiskLevel.R2, emptyMap(), "写", false))
        policy.consumeConfirmation(ApprovalConfirmation("conf-2", ApprovalConfirmation.Decision.APPROVE_SESSION))
        assertEquals(PermissionPolicy.PolicyDecision.Granted,
            policy.decide(ctxOf(pluginId = "p", risk = "R2", capabilityId = "cal.write")))
    }

    @Test
    fun `deny decision consumes without grant`() {
        val policy = PermissionPolicy()
        policy.registerApproval(ApprovalRequest(
            "conf-3", "p", "t", "cal.write", RiskLevel.R2, emptyMap(), "写", false))
        policy.consumeConfirmation(ApprovalConfirmation("conf-3", ApprovalConfirmation.Decision.DENY))
        assertTrue(policy.decide(ctxOf(pluginId = "p", risk = "R2", capabilityId = "cal.write"))
            is PermissionPolicy.PolicyDecision.NeedsApproval)
    }
}
