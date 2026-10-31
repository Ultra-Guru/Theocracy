package com.apex.agent.terminalmcp.tools

import com.apex.agent.platform.TerminalExec.AnsiMode
import com.apex.agent.platform.TerminalExec.AnsiSanitizer
import com.apex.agent.platform.TerminalExec.OutputLimiter
import com.apex.agent.platform.TerminalWait.WaitCondition
import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.errors.asTerminalError
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.terminalmcp.McpToolCallOutcome
import com.apex.agent.terminalmcp.McpToolDescriptor
import com.apex.agent.terminalmcp.McpToolProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * TerminalRuntime → MCP 工具面适配器（terminal 十连 PR 2/10）。
 *
 * 核心工具四件套：
 * - [run]：阻塞式执行（run → WaitEngine 等待退出 → observe RAW 收割输出）。
 *   退出码来自 OSC 633 shell marker（waitpid 确证，绝不用输出静默推断）；
 *   超时不杀进程 —— timed_out=true + partial 输出，Agent 可用 send_signal
 *   打断或后续 process 工具（PR 7/10）继续收割。
 * - [listSessions]：会话簿枚举（snapshot SESSIONS）。
 * - [sendSignal]：前台作业组信号（Ctrl-C 语义：打断当前命令，shell 存活）。
 * - [resize]：PTY 尺寸同步（TIOCSWINSZ + VT resize）。
 *
 * 共享会话：session_id 缺省时复用（或按需创建）默认共享会话 —— Agent 的
 * 短命令（pwd / git status）在同一 shell 里连续执行，保留 cd/环境变量/别名
 * 的连续性；长任务走 PR 7/10 的 process 通道派生独立会话。
 *
 * 安全：本 PR 工具面尚无门控（四级分类器在 PR 4/10、确认门在 PR 5/10 接入
 * 写入链路）—— 当前阶段仅本机持令牌客户端可达。
 */
class TerminalRuntimeTools(
    private val runtime: TerminalRuntime,
) : McpToolProvider {

    @Volatile
    private var sharedSessionId: Long? = null
    private val sharedSessionLock = Any()

    // ─── 工具面元数据 ───

    override suspend fun listTools(): List<McpToolDescriptor> = listOf(
        McpToolDescriptor(
            name = TOOL_RUN,
            description = "Execute a shell command in the persistent terminal and wait for it to finish. " +
                "Reuses the default shared session unless session_id is given (keeps cwd/env/aliases). " +
                "Exit codes come from waitpid-confirmed shell markers. On timeout the process keeps running " +
                "(timed_out=true, partial output returned) — use send_signal to interrupt.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("command") {
                        put("type", "string")
                        put("description", "shell command line to execute")
                    }
                    putJsonObject("session_id") {
                        put("type", "integer")
                        put("description", "target session id (omit to reuse the default shared session)")
                    }
                    putJsonObject("timeout_ms") {
                        put("type", "integer")
                        put("default", DEFAULT_TIMEOUT_MS)
                        put("description", "max wait for command completion; 0 = wait forever")
                    }
                    putJsonObject("max_output_bytes") {
                        put("type", "integer")
                        put("default", DEFAULT_MAX_OUTPUT_BYTES)
                        put("description", "output capture budget (head+tail folding when exceeded)")
                    }
                }
                putJsonArray("required") { add("command") }
            },
        ),
        McpToolDescriptor(
            name = TOOL_LIST_SESSIONS,
            description = "List terminal sessions with state, cwd, size, last exit code and active jobs.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { }
            },
        ),
        McpToolDescriptor(
            name = TOOL_SEND_SIGNAL,
            description = "Send a Unix signal to the session's foreground process group (Ctrl-C semantics: " +
                "interrupt the current command, shell survives). delivered=false means the shell is idle " +
                "(no foreground job) — nothing was sent.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("session_id") {
                        put("type", "integer")
                        put("description", "target session (omit for the default shared session)")
                    }
                    putJsonObject("signal") {
                        put("type", "string")
                        putJsonArray("enum") {
                            UnixSignal.entries.forEach { add(it.name) }
                        }
                        put("description", "signal name")
                    }
                }
                putJsonArray("required") { add("signal") }
            },
        ),
        McpToolDescriptor(
            name = TOOL_RESIZE,
            description = "Resize a session's PTY (TIOCSWINSZ + SIGWINCH). Use before reading wide output " +
                "or when attaching a human view (PR 8/10).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("session_id") {
                        put("type", "integer")
                        put("description", "target session (omit for the default shared session)")
                    }
                    putJsonObject("rows") {
                        put("type", "integer")
                        put("minimum", 2)
                        put("maximum", 200)
                    }
                    putJsonObject("cols") {
                        put("type", "integer")
                        put("minimum", 10)
                        put("maximum", 500)
                    }
                }
                putJsonArray("required") { add("rows"); add("cols") }
            },
        ),
    )

    override suspend fun find(name: String): McpToolDescriptor? =
        listTools().firstOrNull { it.name == name }

    override suspend fun call(name: String, arguments: JsonObject): McpToolCallOutcome = try {
        when (name) {
            TOOL_RUN -> run(arguments)
            TOOL_LIST_SESSIONS -> listSessions()
            TOOL_SEND_SIGNAL -> sendSignal(arguments)
            TOOL_RESIZE -> resize(arguments)
            else -> McpToolCallOutcome(text = "unknown tool: $name", isError = true)
        }
    } catch (t: Throwable) {
        // Runtime 失败（TerminalError typed / legacy 字符串异常）→ 结构化错误面。
        toolError(t)
    }

    // ─── run ───

    private suspend fun run(arguments: JsonObject): McpToolCallOutcome {
        val command = arguments.stringArg("command")
            ?: return invalidParams("run requires string param: command")
        val sessionId = arguments.longArg("session_id") ?: sharedSession()
        val timeoutMs = arguments.longArg("timeout_ms") ?: DEFAULT_TIMEOUT_MS
        val maxOutputBytes = (arguments.intArg("max_output_bytes") ?: DEFAULT_MAX_OUTPUT_BYTES)
            .coerceIn(MIN_OUTPUT_BYTES, MAX_OUTPUT_BYTES_CAP)

        val startedAt = System.currentTimeMillis()
        val runResult = runtime.run(sessionId, command, InputOwner.AGENT).getOrElse { return toolError(it) }
        val waitResult = runtime.wait(
            sessionId,
            WaitCondition.ProcessExited(runResult.jobId),
            timeoutMs = if (timeoutMs <= 0) Long.MAX_VALUE / 2 else timeoutMs,
        ).getOrElse { return toolError(it) }

        var sessionGone = false
        var timedOut = false
        var exitCode: Int? = null
        when (val waited = waitResult) {
            is WaitResult.Matched ->
                exitCode = (waited.event as? TerminalEvent.ProcessExited)?.exitCode
            is WaitResult.Timeout -> timedOut = true
            is WaitResult.SessionGone -> sessionGone = true
        }

        // 输出收割：RAW 观察（起点 = 本 job 的 startCursor）→ ANSI 剥离 → head/tail 折叠。
        val observed = runtime.observe(
            sessionId,
            mode = TerminalRuntime.ObserveMode.RAW,
            afterCursor = runResult.startCursor,
            maxBytes = maxOutputBytes,
        ).getOrNull()
        val sanitized = AnsiSanitizer.sanitize(observed?.raw ?: "", AnsiMode.STRIP)
        val limited = OutputLimiter(
            maxChars = maxOutputBytes,
            headLines = HEAD_LINES,
            tailLines = TAIL_LINES,
        ).limit(sanitized.text)

        val structured = buildJsonObject {
            put("session_id", sessionId)
            put("job_id", runResult.jobId)
            putNullableInt("exit_code", exitCode)
            put("timed_out", timedOut)
            put("truncated", limited.truncated || (observed?.truncated ?: false))
            put("session_gone", sessionGone)
            put("output", limited.text)
            put("duration_ms", System.currentTimeMillis() - startedAt)
        }
        return McpToolCallOutcome(text = "ok", structured = structured)
    }

    // ─── list_sessions ───

    private suspend fun listSessions(): McpToolCallOutcome {
        val snapshot = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrElse { return toolError(it) }
        val sessions = snapshot.sessions.map { state: TerminalSemanticState ->
            buildJsonObject {
                put("session_id", state.session.id)
                put("state", state.session.state.name)
                put("alive", state.session.state.isAliveState)
                put("shell", state.session.shell)
                put("cwd", state.session.cwd)
                put("rows", state.session.rows)
                put("cols", state.session.cols)
                put("created_at", state.session.createdAt)
                putNullableInt("last_exit_code", state.session.lastExitCode)
                state.foregroundJob?.let { job ->
                    putJsonObject("foreground_job") {
                        put("job_id", job.id)
                        put("command", job.command)
                        put("state", job.state.name)
                    }
                }
                put("background_jobs", state.backgroundJobs.size)
            }
        }
        val structured = buildJsonObject { put("sessions", JsonArray(sessions)) }
        return McpToolCallOutcome(text = "ok", structured = structured)
    }

    // ─── send_signal ───

    private suspend fun sendSignal(arguments: JsonObject): McpToolCallOutcome {
        val signalName = arguments.stringArg("signal")
            ?: return invalidParams("send_signal requires string param: signal")
        val signal = UnixSignal.entries.firstOrNull { it.name.equals(signalName, ignoreCase = true) }
            ?: return invalidParams("unknown signal: $signalName (expected one of ${UnixSignal.entries.joinToString { it.name }})")
        val sessionId = arguments.longArg("session_id") ?: sharedSession()
        val result = runtime.signalForeground(sessionId, signal, InputOwner.AGENT)
            .getOrElse { return toolError(it) }
        val structured = buildJsonObject {
            put("session_id", sessionId)
            put("signal", result.signal.name)
            put("delivered", result.foregroundDelivered ?: result.sent)
        }
        return McpToolCallOutcome(text = "ok", structured = structured)
    }

    // ─── resize ───

    private suspend fun resize(arguments: JsonObject): McpToolCallOutcome {
        val rows = arguments.intArg("rows") ?: return invalidParams("resize requires integer param: rows")
        val cols = arguments.intArg("cols") ?: return invalidParams("resize requires integer param: cols")
        if (rows !in 2..200 || cols !in 10..500) {
            return invalidParams("resize out of range: rows 2..200, cols 10..500 (got rows=$rows cols=$cols)")
        }
        val sessionId = arguments.longArg("session_id") ?: sharedSession()
        val result = runtime.resize(sessionId, rows, cols).getOrElse { return toolError(it) }
        val structured = buildJsonObject {
            put("session_id", sessionId)
            put("rows", result.rows)
            put("cols", result.cols)
            put("resized", result.resized)
        }
        return McpToolCallOutcome(text = "ok", structured = structured)
    }

    // ─── 共享会话 ───

    /**
     * 默认共享会话：find-or-create。已存在且未终态时直接复用 —— 保持 cwd、
     * 环境变量、shell 别名的连续性；终态（CLOSED/LOST/EXITED/FAILED/BROKEN）
     * 或不存在时重建。
     */
    private suspend fun sharedSession(): Long {
        synchronized(sharedSessionLock) {
            val existing = sharedSessionId
            if (existing != null && isSessionUsable(existing)) return existing
            val created = runtime.create(rows = SHARED_SESSION_ROWS, cols = SHARED_SESSION_COLS)
                .getOrElse { throw it }
            sharedSessionId = created.sessionId
            return created.sessionId
        }
    }

    private suspend fun isSessionUsable(sessionId: Long): Boolean = try {
        val snapshot = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS).getOrNull()
        val session = snapshot?.sessions?.firstOrNull { it.session.id == sessionId }
        session != null && session.session.state.isAliveState
    } catch (t: Throwable) {
        // 快照失败（runtime 尚未就绪等）→ 保守重建。
        false
    }

    // ─── 错误面 ───

    private fun toolError(t: Throwable): McpToolCallOutcome {
        val terminalError = t.asTerminalError()
        val structured = buildJsonObject {
            put("ok", false)
            putJsonObject("error") {
                put("code", terminalError?.code ?: "InternalError")
                put("message", t.message ?: t.javaClass.simpleName)
                put("recoverable", terminalError?.recoverable ?: false)
            }
        }
        return McpToolCallOutcome(text = structured.toString(), structured = structured, isError = true)
    }

    private fun invalidParams(message: String): McpToolCallOutcome =
        McpToolCallOutcome(text = message, isError = true)

    companion object {
        const val TOOL_RUN = "run"
        const val TOOL_LIST_SESSIONS = "list_sessions"
        const val TOOL_SEND_SIGNAL = "send_signal"
        const val TOOL_RESIZE = "resize"

        /** run 默认等待预算：60s（Agent 客户端工具超时的常见默认值下限）。 */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** 输出默认预算 20KB（termux-mcp 同款基线：保 LLM token 效率，超限 drain 不回传）。 */
        const val DEFAULT_MAX_OUTPUT_BYTES = 20_000
        const val MIN_OUTPUT_BYTES = 512
        const val MAX_OUTPUT_BYTES_CAP = 512_000

        /** head/tail 行折叠预算（头部表头/文件列表 + 尾部错误/汇总价值最高）。 */
        const val HEAD_LINES = 150
        const val TAIL_LINES = 50

        /** 共享会话几何：宽幅减少 Agent 读屏折行（read_screen 在 PR 3/10 接入）。 */
        const val SHARED_SESSION_ROWS = 40
        const val SHARED_SESSION_COLS = 120
    }
}

/** 会话非终态判定（list_sessions 的 alive 语义 + 共享会话可复用判定的共同基础）。 */
private val SessionState.isAliveState: Boolean
    get() = this !in setOf(
        SessionState.EXITED,
        SessionState.LOST,
        SessionState.FAILED,
        SessionState.BROKEN,
        SessionState.CLOSED,
    )

/** 可空 Int 的 JsonObject 写入：null 转 JSON null（避免 Any 类型击穿 put 重载）。 */
internal fun kotlinx.serialization.json.JsonObjectBuilder.putNullableInt(key: String, value: Int?) {
    if (value != null) put(key, value) else put(key, JsonNull)
}

/** 可空 Long 的 JsonObject 写入：null 转 JSON null。 */
internal fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(key: String, value: Long?) {
    if (value != null) put(key, value) else put(key, JsonNull)
}
