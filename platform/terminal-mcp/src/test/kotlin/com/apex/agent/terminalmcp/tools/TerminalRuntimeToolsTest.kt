package com.apex.agent.terminalmcp.tools

import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.errors.TerminalOperationException
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.terminalmcp.McpToolProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalRuntimeTools 单测：MCP 工具面 ↔ TerminalRuntime 的映射语义。
 * 全部基于手写 [FakeRuntime]（脚本化行为 + 调用记录）。
 */
class TerminalRuntimeToolsTest {

    private fun tools(fake: FakeRuntime): McpToolProvider = TerminalRuntimeTools(fake)

    private fun args(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    private fun textArg(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
    private fun intArg(value: Int) = kotlinx.serialization.json.JsonPrimitive(value)

    // ─── run ───

    @Test
    fun `run creates shared session on first call and reuses it`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        val provider = tools(fake)

        val first = provider.call("run", args("command" to textArg("pwd")))
        assertFalse(first.isError)
        assertEquals(1, fake.createCalls)
        assertEquals(100L to "pwd", fake.recordedRuns.single())

        // 第二次 run 复用同一共享会话（不再 create）
        provider.call("run", args("command" to textArg("ls")))
        assertEquals(1, fake.createCalls)
        assertEquals(2, fake.recordedRuns.size)
    }

    @Test
    fun `run rebuilds shared session when previous one is terminal`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        val provider = tools(fake)

        // 第一次 run：创建共享会话 100
        provider.call("run", args("command" to textArg("echo hi")))
        assertEquals(1, fake.createCalls)

        // 会话 100 进入终态 → 下一次 run 必须重建（新会话 101）
        fake.withSession(100, state = SessionState.CLOSED)
        fake.nextSessionId = 101
        provider.call("run", args("command" to textArg("echo again")))

        assertEquals(2, fake.createCalls)
        assertEquals(listOf(100L, 101L), fake.recordedRuns.map { it.first })
    }

    @Test
    fun `run returns waitpid exit code and sanitized output`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100)
            nextRawOutput = "\u001B[32mok\u001B[0m\n"
        }
        val outcome = tools(fake).call("run", args("command" to textArg("true")))
        assertFalse(outcome.isError)
        val structured = outcome.structured!!
        assertEquals(0, structured["exit_code"]!!.jsonPrimitive.content.toInt())
        assertEquals("ok\n", structured["output"]!!.jsonPrimitive.content)
        assertEquals(false, structured["timed_out"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(100L, structured["session_id"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `run timeout reports timed_out with null exit code`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100)
            nextWaitResult = WaitResult.Timeout(waitedMs = 60_000)
        }
        val outcome = tools(fake).call("run", args("command" to textArg("sleep 999")))
        val structured = outcome.structured!!
        assertTrue(structured["timed_out"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(structured["exit_code"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `run session gone is reported`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100)
            nextWaitResult = WaitResult.SessionGone(
                com.apex.agent.platform.terminal.events.CloseCause.BROKEN,
            )
        }
        val outcome = tools(fake).call("run", args("command" to textArg("top")))
        val structured = outcome.structured!!
        assertTrue(structured["session_gone"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `run truncation flags propagate`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100)
            nextObserveTruncated = true
        }
        val outcome = tools(fake).call("run", args("command" to textArg("cat big")))
        val structured = outcome.structured!!
        assertTrue(structured["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `run runtime failure maps to structured terminal error`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100)
            nextRunResult = Result.failure(TerminalOperationException(TerminalError.SessionNotFound))
        }
        val outcome = tools(fake).call("run", args("command" to textArg("ls")))
        assertTrue(outcome.isError)
        val error = outcome.structured!!["error"]!!.jsonObject
        assertEquals("SessionNotFound", error["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `run without command is invalid`() = runTest {
        val fake = FakeRuntime()
        val outcome = tools(fake).call("run", args())
        assertTrue(outcome.isError)
    }

    // ─── list_sessions ───

    @Test
    fun `list sessions maps snapshot fields`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100, cwd = "/sdcard", lastExitCode = 3, foregroundJob = foregroundJob(7, "npm run dev"))
        }
        val outcome = tools(fake).call("list_sessions", args())
        assertFalse(outcome.isError)
        val sessions = outcome.structured!!["sessions"]!!.jsonArray
        assertEquals(1, sessions.size)
        val session = sessions[0].jsonObject
        assertEquals(100L, session["session_id"]!!.jsonPrimitive.content.toLong())
        assertEquals("READY", session["state"]!!.jsonPrimitive.content)
        assertEquals(true, session["alive"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("/sdcard", session["cwd"]!!.jsonPrimitive.content)
        assertEquals(3, session["last_exit_code"]!!.jsonPrimitive.content.toInt())
        assertEquals("npm run dev", session["foreground_job"]!!.jsonObject["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list sessions marks terminal states not alive`() = runTest {
        val fake = FakeRuntime().apply { withSession(100, state = SessionState.LOST) }
        val outcome = tools(fake).call("list_sessions", args())
        val session = outcome.structured!!["sessions"]!!.jsonArray[0].jsonObject
        assertEquals(false, session["alive"]!!.jsonPrimitive.content.toBoolean())
    }

    // ─── send_signal ───

    @Test
    fun `send signal targets shared session foreground group`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        val outcome = tools(fake).call("send_signal", args("signal" to textArg("SIGINT")))
        assertFalse(outcome.isError)
        assertEquals(100L to UnixSignal.SIGINT, fake.recordedSignals.single())
        val structured = outcome.structured!!
        assertEquals("SIGINT", structured["signal"]!!.jsonPrimitive.content)
        assertEquals(true, structured["delivered"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `send signal reports not delivered on idle shell`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100)
            nextSignalResult = Result.success(
                com.apex.agent.platform.terminal.runtime.TerminalRuntime.SignalResult(
                    sent = false, signal = UnixSignal.SIGINT, targetJobId = null, foregroundDelivered = false,
                ),
            )
        }
        val outcome = tools(fake).call("send_signal", args("signal" to textArg("SIGINT")))
        assertEquals(false, outcome.structured!!["delivered"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `send signal unknown name is error`() = runTest {
        val fake = FakeRuntime()
        val outcome = tools(fake).call("send_signal", args("signal" to textArg("SIGUSR42")))
        assertTrue(outcome.isError)
    }

    // ─── resize ───

    @Test
    fun `resize records geometry and maps result`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        val outcome = tools(fake).call("resize", args("rows" to intArg(30), "cols" to intArg(200)))
        assertFalse(outcome.isError)
        assertEquals(Triple(100L, 30, 200), fake.recordedResizes.single())
        assertEquals(true, outcome.structured!!["resized"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `resize out of range is rejected`() = runTest {
        val fake = FakeRuntime()
        val outcome = tools(fake).call("resize", args("rows" to intArg(1), "cols" to intArg(80)))
        assertTrue(outcome.isError)
    }

    // ─── 工具面元数据 ───

    @Test
    fun `tool registry exposes four core tools with schemas`() = runTest {
        val descriptors = tools(FakeRuntime()).listTools()
        assertEquals(
            listOf("run", "list_sessions", "send_signal", "resize"),
            descriptors.map { it.name },
        )
        descriptors.forEach {
            assertEquals("object", it.inputSchema["type"]!!.jsonPrimitive.content)
            assertTrue(it.description.isNotEmpty())
        }
        val runSchema = descriptors.first { it.name == "run" }.inputSchema
        assertTrue(runSchema["properties"]!!.jsonObject.containsKey("command"))
        assertNotNull(descriptors.first { it.name == "run" }.inputSchema["required"])
    }

    @Test
    fun `find resolves known tools only`() = runTest {
        val provider = tools(FakeRuntime())
        assertNotNull(provider.find("run"))
        assertNull(provider.find("read_screen"))
    }
}
