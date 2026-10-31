package com.apex.agent.terminalmcp.tools

import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.errors.TerminalError
import com.apex.agent.platform.terminal.errors.TerminalOperationException
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.terminalmcp.McpToolProvider
import com.apex.agent.terminalmcp.security.SecurityGate
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

    // ─── read_screen ───

    @Test
    fun `read screen maps semantic fields`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100, cwd = "/fallback/cwd", lastExitCode = 3, foregroundJob = foregroundJob(7, "npm run dev"))
            nextScreenState = com.apex.agent.platform.terminal.screen.TerminalScreenState(
                rows = 40, cols = 120, cursorRow = 3, cursorCol = 7,
                alternateScreen = false, cursorVisible = true, title = "theocracy",
                renderedText = "line1\nline2", changedRows = null,
                scrollbackLineCount = 12, guestCwd = "/semantic/cwd",
            )
        }
        val outcome = tools(fake).call(
            "read_screen",
            args("session_id" to intArg(100), "include_scrollback" to kotlinx.serialization.json.JsonPrimitive(true)),
        )
        assertFalse(outcome.isError)
        val structured = outcome.structured!!
        // cwd 优先引擎值（OSC 7 直解），fallback 到 reducer 侧车
        assertEquals("/semantic/cwd", structured["cwd"]!!.jsonPrimitive.content)
        assertEquals(3, structured["cursor"]!!.jsonObject["row"]!!.jsonPrimitive.content.toInt())
        assertEquals(7, structured["cursor"]!!.jsonObject["col"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, structured["alternate_screen"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("theocracy", structured["title"]!!.jsonPrimitive.content)
        val live = structured["live_screen"]!!.jsonArray
        assertEquals(listOf("line1", "line2"), live.map { it.jsonPrimitive.content })
        assertEquals(3, structured["last_exit_code"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, structured["has_foreground_process"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `read screen falls back to session cwd when engine guest cwd is null`() = runTest {
        // 设备端 native 引擎不解析 OSC 7（guestCwd=null）—— reducer 侧车值兜底
        val fake = FakeRuntime().apply {
            withSession(100, cwd = "/sidecar/cwd")
            nextScreenState = com.apex.agent.platform.terminal.screen.TerminalScreenState(
                rows = 24, cols = 80, cursorRow = 0, cursorCol = 0,
                alternateScreen = false, title = null, renderedText = "$ ", changedRows = null,
            )
        }
        val outcome = tools(fake).call("read_screen", args("session_id" to intArg(100)))
        assertEquals("/sidecar/cwd", outcome.structured!!["cwd"]!!.jsonPrimitive.content)
        // 未请求滚回时不携带 scrollback 字段
        assertFalse(outcome.structured!!.containsKey("scrollback"))
    }

    @Test
    fun `read screen include scrollback returns tail lines`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100)
            nextScreenState = com.apex.agent.platform.terminal.screen.TerminalScreenState(
                rows = 24, cols = 80, cursorRow = 0, cursorCol = 0,
                alternateScreen = false, title = null, renderedText = "", changedRows = null,
                scrollbackLineCount = 120,
            )
            nextScrollbackTail = listOf("old1", "old2")
        }
        val outcome = tools(fake).call(
            "read_screen",
            args("session_id" to intArg(100), "include_scrollback" to kotlinx.serialization.json.JsonPrimitive(true)),
        )
        val structured = outcome.structured!!
        assertEquals(listOf("old1", "old2"), structured["scrollback"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(120, structured["scrollback_lines_total"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `read screen observe failure maps to tool error`() = runTest {
        val fake = FakeRuntime().apply {
            withSession(100)
            observeError = TerminalOperationException(TerminalError.SessionNotFound)
        }
        val outcome = tools(fake).call("read_screen", args("session_id" to intArg(999)))
        assertTrue(outcome.isError)
        assertEquals("SessionNotFound", outcome.structured!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    // ─── write ───

    @Test
    fun `write line records input to shared session`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        val outcome = tools(fake).call("write", args("text" to textArg("y")))
        assertFalse(outcome.isError)
        assertEquals(
            listOf(Triple(100L, com.apex.agent.platform.terminal.runtime.TerminalRuntime.WriteKind.LINE, "y")),
            fake.recordedWrites,
        )
        assertEquals(true, outcome.structured!!["written"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `write raw kind is honored`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        tools(fake).call("write", args("text" to textArg("\u0003"), "kind" to textArg("raw")))
        assertEquals(
            Triple(100L, com.apex.agent.platform.terminal.runtime.TerminalRuntime.WriteKind.RAW, "\u0003"),
            fake.recordedWrites.single(),
        )
    }

    @Test
    fun `write invalid kind is rejected`() = runTest {
        val fake = FakeRuntime()
        val outcome = tools(fake).call("write", args("text" to textArg("x"), "kind" to textArg("key")))
        assertTrue(outcome.isError)
    }

    // ─── 工具面元数据 ───

    @Test
    fun `tool registry exposes eight tools with schemas`() = runTest {
        val descriptors = tools(FakeRuntime()).listTools()
        assertEquals(
            listOf("run", "list_sessions", "send_signal", "resize", "read_screen", "write", "gate_status", "events"),
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
        assertNotNull(provider.find("read_screen"))
        assertNull(provider.find("process_spawn"))
    }

    // ─── 安全门控（T-GATE）───

    @Test
    fun `run dangerous command returns confirmation_required and does not execute`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        val gate = com.apex.agent.terminalmcp.security.SecurityGate()
        val provider = TerminalRuntimeTools(fake, gate)

        val outcome = provider.call("run", args("command" to textArg("rm -rf /sdcard/tmpdir")))
        assertTrue(outcome.isError)
        assertEquals("confirmation_required", outcome.structured!!["status"]!!.jsonPrimitive.content)
        assertNotNull(outcome.structured!!["confirm_id"])
        assertEquals("DANGEROUS", outcome.structured!!["risk"]!!.jsonPrimitive.content)
        // 命令未进入 PTY（写入前拦截的铁律）
        assertTrue("gated command must NOT reach the runtime", fake.recordedRuns.isEmpty())
    }

    @Test
    fun `approved dangerous command executes on retry`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        val gate = com.apex.agent.terminalmcp.security.SecurityGate()
        val provider = TerminalRuntimeTools(fake, gate)

        val first = provider.call("run", args("command" to textArg("pm clear com.some.app")))
        assertTrue(first.isError)
        val confirmId = first.structured!!["confirm_id"]!!.jsonPrimitive.content

        // 人侧批准 → 同命令重试 → 执行
        gate.resolve(confirmId, approved = true)
        val second = provider.call("run", args("command" to textArg("pm clear com.some.app")))
        assertFalse(second.isError)
        assertEquals(1, fake.recordedRuns.size)
    }

    @Test
    fun `run critical command is blocked`() = runTest {
        val fake = FakeRuntime()
        val provider = TerminalRuntimeTools(fake, com.apex.agent.terminalmcp.security.SecurityGate())
        val outcome = provider.call("run", args("command" to textArg("rm -rf /")))
        assertTrue(outcome.isError)
        assertEquals("blocked", outcome.structured!!["status"]!!.jsonPrimitive.content)
        assertEquals("CRITICAL", outcome.structured!!["risk"]!!.jsonPrimitive.content)
        assertTrue(fake.recordedRuns.isEmpty())
    }

    @Test
    fun `gate status lists pending confirmations`() = runTest {
        val fake = FakeRuntime()
        val gate = com.apex.agent.terminalmcp.security.SecurityGate()
        val provider = TerminalRuntimeTools(fake, gate)
        provider.call("run", args("command" to textArg("kill 1234")))

        val outcome = provider.call("gate_status", args())
        assertFalse(outcome.isError)
        assertEquals(1, outcome.structured!!["pending_count"]!!.jsonPrimitive.content.toInt())
        assertEquals("kill 1234", outcome.structured!!["pending"]!!.jsonArray[0].jsonObject["command"]!!.jsonPrimitive.content)

        // 单条查询
        val confirmId = outcome.structured!!["pending"]!!.jsonArray[0].jsonObject["confirm_id"]!!.jsonPrimitive.content
        val single = provider.call("gate_status", args("confirm_id" to textArg(confirmId)))
        assertEquals("pending", single.structured!!["status"]!!.jsonPrimitive.content)

        // 未知 id
        val unknown = provider.call("gate_status", args("confirm_id" to textArg("nope")))
        assertEquals("unknown_or_expired", unknown.structured!!["status"]!!.jsonPrimitive.content)
    }

    // ─── 事件流（T-JOURNAL）───

    @Test
    fun `run produces replayable proposed-gate-executed chain in journal`() = runTest {
        val fake = FakeRuntime().apply { withSession(100) }
        val dir = kotlin.io.path.createTempDirectory("journal").toFile()
        val journal = com.apex.agent.terminalmcp.event.EventJournal(dir)
        val metrics = com.apex.agent.terminalmcp.event.ToolMetrics()
        val provider = TerminalRuntimeTools(fake, SecurityGate(), journal, metrics)

        val outcome = provider.call("run", args("command" to textArg("echo hi")))
        assertFalse(outcome.isError)

        val eventsResult = provider.call("events", args())
        val events = eventsResult.structured!!["events"]!!.jsonArray
        val types = events.map { it.jsonObject["event"]!!.jsonPrimitive.content }
        assertTrue(types.contains("command_proposed"))
        assertTrue(types.contains("gate_decision"))
        assertTrue(types.contains("command_executed"))
        assertTrue(types.contains("tool_call"))
        // 提案事件携带命令与会话
        val proposed = events.first { it.jsonObject["event"]!!.jsonPrimitive.content == "command_proposed" }.jsonObject
        assertEquals("echo hi", proposed["cmd"]!!.jsonPrimitive.content)
        assertEquals(100L, proposed["session"]!!.jsonPrimitive.content.toLong())
        // 度量窗口有记录
        assertTrue(eventsResult.structured!!["metrics"]!!.jsonObject.containsKey("run"))
    }

    @Test
    fun `events tool filters by type and pages by seq`() = runTest {
        val fake = FakeRuntime()
        val dir = kotlin.io.path.createTempDirectory("journal").toFile()
        val journal = com.apex.agent.terminalmcp.event.EventJournal(dir)
        journal.record("tool_call", "tool" to "list_sessions", "duration_ms" to 5, "error" to false)
        journal.record("command_proposed", "cmd" to "ls")
        journal.record("tool_call", "tool" to "run", "duration_ms" to 9, "error" to false)
        val provider = TerminalRuntimeTools(fake, SecurityGate(), journal)

        val filtered = provider.call("events", args("event_type" to textArg("tool_call")))
        val toolCalls = filtered.structured!!["events"]!!.jsonArray
        assertEquals(2, toolCalls.size)

        // 游标翻页：仅取 seq 大于第二条的
        val secondSeq = toolCalls[1].jsonObject["seq"]!!.jsonPrimitive.content.toLong()
        val next = provider.call("events", args("since_seq" to kotlinx.serialization.json.JsonPrimitive(secondSeq)))
        assertTrue(next.structured!!["events"]!!.jsonArray.all {
            it.jsonObject["seq"]!!.jsonPrimitive.content.toLong() > secondSeq
        })
    }

    @Test
    fun `events tool reports journal not configured when absent`() = runTest {
        val provider = tools(FakeRuntime())
        val outcome = provider.call("events", args())
        assertTrue(outcome.isError)
    }
}
