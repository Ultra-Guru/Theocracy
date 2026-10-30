package com.apex.agent.platform.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P71 — REAL-JNI instrumentation tests for the generalized argv spawn path (N1).
 *
 * Chain under test:
 *   Kotlin → NativePty.nativeCreateSessionArgv (JNI) → PtyEngine::createSessionArgv
 *   → PtySession(argv ctor) → forkpty → execv(argv[0], argv) → PTY.
 *
 * Coverage:
 *   1. argv=["/system/bin/sh","-c","echo …"] — non-interactive exec, output + exit;
 *   2. argv=["/system/bin/sh","-i"] — interactive parity with the legacy entry;
 *   3. explicit env overrides reach the child;
 *   4. exec failure → child exits 127;
 *   5. two argv sessions stay isolated;
 *   6. device-side spawn latency numbers (P71 benchmark data).
 *
 * （历史：libproot 打包校验与 PRoot 全链路验证已随 Ubuntu/PRoot 栈剥离移除。）
 *
 * CI LIMITATION: no emulator in this repo's CI — compiled there
 * (compileDebugAndroidTestKotlin), executed on device via
 * `:platform:terminal:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class NativePtyArgvInstrumentationTest {

    private companion object {
        const val READ_CHUNK = 8192
        const val WAIT_MS = 5000
    }

    private val pty = NativePty()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun workDir(): String = context.filesDir.absolutePath

    private fun readOnce(sessionId: Int): Pair<Int, ByteArray> {
        val status = IntArray(3)
        val data = pty.nativeReadBytes(sessionId, READ_CHUNK, status)
        return status[0] to data
    }

    private fun drainFor(sessionId: Int, timeoutMs: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val (st, data) = readOnce(sessionId)
            if (st == 0 && data.isNotEmpty()) out.write(data)
            else if (st == 2 || st == 3 || st == 4) break
            else Thread.sleep(20)
        }
        return out.toByteArray()
    }

    private fun waitForExit(sessionId: Int, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!pty.nativeIsAlive(sessionId)) return pty.nativeGetExitCode(sessionId)
            Thread.sleep(20)
        }
        return -1
    }

    // ═══════════════ 1. argv non-interactive exec ═══════════════

    @Test
    fun argvShDashCEchoesAndExits() {
        val id = pty.nativeCreateSessionArgv(
            arrayOf("/system/bin/sh", "-c", "echo P71_ARGV_OK"),
            workDir(), null, null, 24, 80
        )
        assertTrue("argv session should be created", id > 0)
        try {
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            val out = drainFor(id, 5000)
            val text = String(out, Charsets.UTF_8)
            assertTrue("echo output must arrive (got: '$text')", text.contains("P71_ARGV_OK"))

            val exit = waitForExit(id, WAIT_MS.toLong())
            assertEquals("sh -c echo must exit 0", 0, exit)
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    // ═══════════════ 2. interactive parity with legacy entry ═══════════════

    @Test
    fun argvInteractiveShellMatchesLegacyBehavior() {
        val id = pty.nativeCreateSessionArgv(
            arrayOf("/system/bin/sh", "-i"),
            workDir(), null, null, 24, 80
        )
        assertTrue(id > 0)
        try {
            // shell starts alive and idles (P70-1 semantics hold on the argv path)
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            drainFor(id, 500)
            val (st, _) = readOnce(id)
            assertTrue("idle argv session must report NO_DATA(1), got $st", st == 1)
            assertTrue(pty.nativeIsAlive(id))

            // write a command → output comes back
            val cmd = "echo argv-interactive-marker"
            assertTrue(pty.nativeWriteBytes(id, (cmd + "\n").toByteArray(), 0, cmd.length + 1))
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            val out = drainFor(id, 5000)
            assertTrue(
                "marker must round-trip (got: '${String(out, Charsets.UTF_8)}')",
                String(out, Charsets.UTF_8).contains("argv-interactive-marker")
            )
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    // ═══════════════ 3. env overrides reach the child ═══════════════

    @Test
    fun argvEnvOverridesReachChild() {
        val id = pty.nativeCreateSessionArgv(
            arrayOf("/system/bin/sh", "-c", "echo V=\$P71_CUSTOM_ENV"),
            workDir(),
            arrayOf("P71_CUSTOM_ENV", "TERM"),
            arrayOf("p71-value", "dumb"),
            24, 80
        )
        assertTrue(id > 0)
        try {
            pty.nativeWaitForData(id, WAIT_MS.toInt())
            val out = drainFor(id, 5000)
            val text = String(out, Charsets.UTF_8)
            assertTrue("custom env must reach child (got: '$text')", text.contains("V=p71-value"))
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    // ═══════════════ 4. exec failure → exit 127 ═══════════════

    @Test
    fun argvExecFailureExits127() {
        val id = pty.nativeCreateSessionArgv(
            arrayOf("/nonexistent/p71/binary"),
            workDir(), null, null, 24, 80
        )
        assertTrue(id > 0)
        try {
            val exit = waitForExit(id, WAIT_MS.toLong())
            assertEquals("execv failure must exit 127 (shell convention)", 127, exit)
        } finally {
            pty.nativeCloseSession(id)
        }
    }

    // ═══════════════ 5. two argv sessions isolated ═══════════════

    @Test
    fun twoArgvSessionsStayIsolated() {
        val a = pty.nativeCreateSessionArgv(
            arrayOf("/system/bin/sh", "-i"), workDir(), null, null, 24, 80
        )
        val b = pty.nativeCreateSessionArgv(
            arrayOf("/system/bin/sh", "-i"), workDir(), null, null, 24, 80
        )
        assertTrue(a > 0); assertTrue(b > 0)
        try {
            pty.nativeWaitForData(a, WAIT_MS.toInt()); drainFor(a, 500)
            pty.nativeWaitForData(b, WAIT_MS.toInt()); drainFor(b, 500)

            val cmdA = "echo marker-A-only"
            val cmdB = "echo marker-B-only"
            assertTrue(pty.nativeWriteBytes(a, (cmdA + "\n").toByteArray(), 0, cmdA.length + 1))
            assertTrue(pty.nativeWriteBytes(b, (cmdB + "\n").toByteArray(), 0, cmdB.length + 1))

            pty.nativeWaitForData(a, WAIT_MS.toInt())
            val outA = String(drainFor(a, 5000), Charsets.UTF_8)
            pty.nativeWaitForData(b, WAIT_MS.toInt())
            val outB = String(drainFor(b, 5000), Charsets.UTF_8)

            assertTrue("A must see its own marker: '$outA'", outA.contains("marker-A-only"))
            assertFalse("A must NOT see B's marker: '$outA'", outA.contains("marker-B-only"))
            assertTrue("B must see its own marker: '$outB'", outB.contains("marker-B-only"))
            assertFalse("B must NOT see A's marker: '$outB'", outB.contains("marker-A-only"))
        } finally {
            pty.nativeCloseSession(a); pty.nativeCloseSession(b)
        }
    }

    // ═══════════════ 8. spawn latency benchmark (device) ═══════════════

    @Test
    fun argvSpawnLatencyBenchmark() {
        // forkpty + execv + child 退出的完整往返耗时（不含 proot）——
        // P71 基准数据的 fork+exec 下界项（PR #75 计划 §20）。
        val samples = mutableListOf<Long>()
        repeat(10) {
            val t0 = System.currentTimeMillis()
            val id = pty.nativeCreateSessionArgv(
                arrayOf("/system/bin/sh", "-c", "true"), workDir(), null, null, 24, 80
            )
            assertTrue(id > 0)
            waitForExit(id, WAIT_MS.toLong())
            pty.nativeCloseSession(id)
            samples.add(System.currentTimeMillis() - t0)
        }
        println("═══ P71 device spawn benchmark (forkpty+execv sh -c true, ${samples.size} runs) ═══")
        println("samples(ms): $samples")
        println("min=${samples.min()}ms avg=${samples.average().toLong()}ms max=${samples.max()}ms")
        assertTrue("spawn round-trip must stay under 5s", samples.max()!! < 5000)
    }
}
