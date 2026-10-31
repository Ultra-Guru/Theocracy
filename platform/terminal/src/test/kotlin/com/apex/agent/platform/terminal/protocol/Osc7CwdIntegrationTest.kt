package com.apex.agent.platform.terminal.protocol

import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.runtime.TerminalRuntimeImpl
import com.apex.agent.platform.terminal.screen.RealVirtualTerminal
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-OSC7 — OSC 7 cwd 侧车全链路（terminal 十连 PR 3/10）。
 *
 * 两条通道各验一条：
 * 1. Agent 命令包装行（ShellMarkers 双标记：633 退出码 + 7 cwd）—— enableShellMarkers
 *    开启时的 run 路径，fake 的 printf 模拟会同时发两帧；
 * 2. rc 的 cd() 函数 / 启动帧 —— 与 marker 协议无关（enableShellMarkers 关闭），
 *    用 injectOutput 直接注入 rc 风格帧模拟。
 *
 * 断言两级：SessionSnapshot.cwd（reducer 侧车 —— 设备端 native 引擎的唯一
 * cwd 来源）与 observe(SCREEN).guestCwd（JVM 引擎直解 + ObservationEngine
 * 的 reducer 回填合并）。
 */
class Osc7CwdIntegrationTest {

    private fun newRuntime(markers: Boolean): Pair<TerminalRuntimeImpl, FakeNativePty> {
        val fake = FakeNativePty()
        val rt = TerminalRuntimeImpl(
            native = fake,
            policy = TerminalPolicyImpl(),
            virtualTerminalFactory = { r, c -> RealVirtualTerminal(r, c) },
            enableShellMarkers = markers
        )
        return rt to fake
    }

    private suspend fun awaitCwd(rt: TerminalRuntimeImpl, sessionId: Long, expected: String) {
        withTimeout(10_000) {
            while (true) {
                val snap = rt.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrThrow()
                val cwd = snap.sessions.firstOrNull { it.session.id == sessionId }?.session?.cwd
                if (cwd == expected) return@withTimeout
                delay(50)
            }
        }
    }

    @Test
    fun `agent wrapped command updates cwd via dual-marker printf`() = runBlocking {
        val (rt, _) = newRuntime(markers = true)
        try {
            val s = rt.create(cwd = "/sdcard").getOrThrow()
            delay(150)
            rt.run(s.sessionId, "cd /data/local/tmp", InputOwner.AGENT).getOrThrow()
            // reducer 侧车（设备端 native 引擎的 cwd 来源）
            awaitCwd(rt, s.sessionId, "/data/local/tmp")
            // observe(SCREEN) 的 guestCwd（引擎直解 + 合并回填，两者在此同值）
            val screen = rt.observe(s.sessionId, TerminalRuntime.ObserveMode.SCREEN).getOrThrow()
            assertEquals("/data/local/tmp", screen.screen?.guestCwd)
        } finally {
            rt.shutdown()
        }
    }

    @Test
    fun `rc-style injected osc7 frame updates cwd without markers`() = runBlocking {
        val (rt, fake) = newRuntime(markers = false)
        try {
            val s = rt.create(cwd = "/sdcard").getOrThrow()
            delay(150)
            val nativeId = rt.sessionManager.assembly(s.sessionId)?.nativeSessionId
                ?: error("native id missing for session ${s.sessionId}")
            // 模拟 rc 的 cd() 函数输出（与 marker 协议完全无关的独立通道）
            fake.injectOutput(nativeId, "\u001b]7;file:///data/user/0/files\u0007")
            awaitCwd(rt, s.sessionId, "/data/user/0/files")
        } finally {
            rt.shutdown()
        }
    }
}
