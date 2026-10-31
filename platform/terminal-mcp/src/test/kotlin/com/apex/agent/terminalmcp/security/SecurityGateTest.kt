package com.apex.agent.terminalmcp.security

import com.apex.agent.terminalmcp.security.SecurityGate.GateDecision
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-GATE — 写入前安全门控单测（terminal 十连 PR 5/10）。
 *
 * 时钟注入（var now）保证 TTL 语义零 sleep 验证：待确认单 5min / 批准票 120s /
 * 一次性消费 / 同命令去重 / 拒绝后重开。
 */
class SecurityGateTest {

    private var now = 1_000_000L
    private val gate = SecurityGate(clock = { now })

    @Test
    fun `read-only and mutating commands are allowed without confirmation`() = runTest {
        assertTrue(gate.evaluate("ls -la", null) is GateDecision.Allowed)
        assertTrue(gate.evaluate("mkdir newdir", null) is GateDecision.Allowed)
        assertTrue(gate.evaluate("cat f | grep x", null) is GateDecision.Allowed)
        assertTrue(gate.pendingList().isEmpty())
    }

    @Test
    fun `dangerous command requires confirmation and does not execute`() = runTest {
        val decision = gate.evaluate("rm -rf /sdcard/tmpdir", null)
        assertTrue(decision is GateDecision.RequireConfirmation)
        val confirmId = (decision as GateDecision.RequireConfirmation).confirmId
        assertTrue(confirmId.isNotEmpty())
        assertEquals(1, gate.pendingList().size)
        assertNotNull(gate.findPending(confirmId))
    }

    @Test
    fun `critical command is blocked unconditionally`() = runTest {
        val decision = gate.evaluate("rm -rf /", null)
        assertTrue(decision is GateDecision.Blocked)
        // 不可确认绕过：没有待确认单产生
        assertTrue(gate.pendingList().isEmpty())
    }

    @Test
    fun `same dangerous command reuses pending confirmation id`() = runTest {
        val first = gate.evaluate("pm clear com.some.app", null) as GateDecision.RequireConfirmation
        val second = gate.evaluate("pm clear com.some.app", null) as GateDecision.RequireConfirmation
        assertEquals(first.confirmId, second.confirmId)
        assertEquals(1, gate.pendingList().size)
    }

    @Test
    fun `approval is one-shot and expires`() = runTest {
        // 第一次：待确认
        val decision = gate.evaluate("input tap 540 960", null) as GateDecision.RequireConfirmation
        // 人侧批准
        assertTrue(gate.resolve(decision.confirmId, approved = true))
        assertEquals(1, gate.activeApprovalCount())
        // 批准后 60s 内重试同命令：放行（票消费）
        now += 60_000
        assertTrue(gate.evaluate("input tap 540 960", null) is GateDecision.Allowed)
        assertEquals(0, gate.activeApprovalCount())
        // 第三次：票已消费 → 重新待确认（一次性）
        val third = gate.evaluate("input tap 540 960", null) as GateDecision.RequireConfirmation
        assertTrue(third.confirmId.isNotEmpty())
    }

    @Test
    fun `approval ticket expires after 120s`() = runTest {
        val decision = gate.evaluate("chmod 777 script.sh", null) as GateDecision.RequireConfirmation
        assertTrue(gate.resolve(decision.confirmId, approved = true))
        now += 121_000
        // 票过期 → 重新待确认（不是放行）
        assertTrue(gate.evaluate("chmod 777 script.sh", null) is GateDecision.RequireConfirmation)
    }

    @Test
    fun `denial removes pending and produces no approval`() = runTest {
        val decision = gate.evaluate("kill 1234", null) as GateDecision.RequireConfirmation
        assertTrue(gate.resolve(decision.confirmId, approved = false))
        assertEquals(0, gate.pendingList().size)
        assertEquals(0, gate.activeApprovalCount())
        // 重试 → 新待确认单（不同 confirmId）
        val retry = gate.evaluate("kill 1234", null) as GateDecision.RequireConfirmation
        assertTrue(retry.confirmId != decision.confirmId)
    }

    @Test
    fun `pending confirmation expires after 5 minutes`() = runTest {
        val decision = gate.evaluate("pm uninstall com.some.app", null) as GateDecision.RequireConfirmation
        now += 5 * 60_000 + 1
        assertNull(gate.findPending(decision.confirmId))
        // 过期后再评估：新单（惰性清理生效）
        val fresh = gate.evaluate("pm uninstall com.some.app", null) as GateDecision.RequireConfirmation
        assertTrue(fresh.confirmId != decision.confirmId)
    }

    @Test
    fun `resolve on unknown or expired id returns false`() = runTest {
        assertEquals(false, gate.resolve("nonexistent", approved = true))
        val decision = gate.evaluate("umount /sdcard", null) as GateDecision.RequireConfirmation
        now += 5 * 60_000 + 1
        assertEquals(false, gate.resolve(decision.confirmId, approved = true))
    }

    @Test
    fun `pending flow reflects gate state`() = runTest {
        assertEquals(0, gate.pendingFlow.value.size)
        val decision = gate.evaluate("dd if=/dev/zero of=/sdcard/blank.img", null) as GateDecision.RequireConfirmation
        assertEquals(1, gate.pendingFlow.value.size)
        assertEquals(decision.confirmId, gate.pendingFlow.value.first().confirmId)
        gate.resolve(decision.confirmId, approved = true)
        assertEquals(0, gate.pendingFlow.value.size)
    }

    @Test
    fun `command hash is stable and collision-resistant prefix`() {
        assertEquals(SecurityGate.hashCommand("rm -rf /"), SecurityGate.hashCommand("rm -rf /"))
        assertTrue(SecurityGate.hashCommand("rm -rf /") != SecurityGate.hashCommand("rm -rf /sdcard"))
        assertEquals(16, SecurityGate.hashCommand("ls").length)
    }
}
