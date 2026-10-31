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
import com.apex.agent.terminalmcp.event.EventJournal
import com.apex.agent.terminalmcp.event.ToolMetrics
import com.apex.agent.terminalmcp.security.SecurityGate
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * TerminalRuntime → MCP 工具面适配器（terminal 十连 PR 2/10）。
 *
 * 核心工具六件套（PR 2/10 四件 + PR 3/10 read_screen/write）：
 * - [run]：阻塞式执行（run → WaitEngine 等待退出 → observe RAW 收割输出）。
 *   退出码来自 OSC 633 shell marker（waitpid 确证，绝不用输出静默推断）；
 *   超时不杀进程 —— timed_out=true + partial 输出，Agent 可用 send_signal
 *   打断或后续 process 工具（PR 7/10）继续收割。
 * - [listSessions]：会话簿枚举（snapshot SESSIONS）。
 * - [sendSignal]：前台作业组信号（Ctrl-C 语义：打断当前命令，shell 存活）。
 * - [resize]：PTY 尺寸同步（TIOCSWINSZ + VT resize）。
 * - [readScreen]：语义化屏幕（live_screen 行数组 + 光标 + alt screen + cwd
 *   + 最近退出码 + stable scrollback）—— Agent 读屏不再拿原始 ANSI 字节。
 * - [writeInput]：交互输入（run 超时后读屏 → 看到 Password:/Continue? → 写入）。
 *
 * 共享会话：session_id 缺省时复用（或按需创建）默认共享会话 —— Agent 的
 * 短命令（pwd / git status）在同一 shell 里连续执行，保留 cd/环境变量/别名
 * 的连续性；长任务走 PR 7/10 的 process 通道派生独立会话。
 *
 * 安全：T-GATE（PR 5/10）写入前门控 —— run 经 [SecurityGate] 评估
 * （ReadOnly/Mutating 自动放行、Dangerous 人工确认（一次性批准票）、
 * Critical 硬拒）；平台层另有 TerminalPolicyImpl 的 CRITICAL 硬拒兜底
 * （PR 4/10，纵深防御第二道边界）。
 */
class TerminalRuntimeTools(
    private val runtime: TerminalRuntime,
    /** T-GATE（PR 5/10）：写入前门控 —— 命令进入 PTY 前评估；Dangerous 人工确认、Critical 硬拒。 */
    private val securityGate: SecurityGate = SecurityGate(),
    /** T-JOURNAL（PR 6/10）：JSONL 事件流（null = 不落盘 —— 既有测试默认形态）。 */
    private val journal: EventJournal? = null,
    /** T-JOURNAL：每工具 p50/p95 延迟与错误率（内存窗口）。 */
    private val metrics: ToolMetrics? = null,
) : McpToolProvider {

    @Volatile
    private var sharedSessionId: Long? = null

    /** 协程锁：Kotlin 2.x 禁止 synchronized 块内的挂起点（锁会跨挂起持有）。 */
    private val sharedSessionMutex = kotlinx.coroutines.sync.Mutex()

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
                putJsonArray("required") { add(JsonPrimitive("command")) }
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
                            UnixSignal.entries.forEach { add(JsonPrimitive(it.name)) }
                        }
                        put("description", "signal name")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("signal")) }
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
                putJsonArray("required") { add(JsonPrimitive("rows")); add(JsonPrimitive("cols")) }
            },
        ),
        McpToolDescriptor(
            name = TOOL_READ_SCREEN,
            description = "Read the terminal's semantic screen state (not raw ANSI bytes): live screen " +
                "rows, cursor position, alternate-screen flag, cwd (OSC 7), last exit code, " +
                "and optionally the stable scrollback tail. Use after a timed-out run to see " +
                "what the command is waiting for (prompts, passwords, progress).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("session_id") {
                        put("type", "integer")
                        put("description", "target session (omit for the default shared session)")
                    }
                    putJsonObject("include_scrollback") {
                        put("type", "boolean")
                        put("default", false)
                        put("description", "include the stable scrollback tail (lines already scrolled off)")
                    }
                    putJsonObject("scrollback_lines") {
                        put("type", "integer")
                        put("default", 50)
                        put("minimum", 1)
                        put("maximum", 500)
                    }
                }
            },
        ),
        McpToolDescriptor(
            name = TOOL_WRITE,
            description = "Write interactive input to a session (kind=line sends with Enter; kind=raw " +
                "sends bytes as-is for control sequences). Use after read_screen shows the command " +
                "waiting for input (Password:, [Y/n], heredoc terminator).",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("session_id") {
                        put("type", "integer")
                        put("description", "target session (omit for the default shared session)")
                    }
                    putJsonObject("text") {
                        put("type", "string")
                        put("description", "input text to write")
                    }
                    putJsonObject("kind") {
                        put("type", "string")
                        putJsonArray("enum") { add(JsonPrimitive("line")); add(JsonPrimitive("raw")) }
                        put("default", "line")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("text")) }
            },
        ),
        McpToolDescriptor(
            name = TOOL_GATE_STATUS,
            description = "Inspect the security gate: pending confirmations (Dangerous commands " +
                "awaiting human approval) and their expiry. After a run returns confirmation_required, " +
                "poll this tool — once the human approves, retry the same command.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("confirm_id") {
                        put("type", "string")
                        put("description", "optional: query one confirmation by id instead of listing all")
                    }
                }
            },
        ),
        McpToolDescriptor(
            name = TOOL_EVENTS,
            description = "Read the audit event journal (JSONL): command_proposed, gate_decision, " +
                "command_executed, tool_call entries with risk levels, exit codes and durations. " +
                "Filter by event_type, page with since_seq. Also returns per-tool p50/p95 latency metrics.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("default", 50)
                        put("maximum", 500)
                    }
                    putJsonObject("event_type") {
                        put("type", "string")
                        put("enum") {
                            add(JsonPrimitive("command_proposed")); add(JsonPrimitive("gate_decision"))
                            add(JsonPrimitive("command_executed")); add(JsonPrimitive("tool_call"))
                        }
                    }
                    putJsonObject("since_seq") {
                        put("type", "integer")
                        put("description", "only entries with seq greater than this (cursor paging)")
                    }
                }
            },
        ),
    )

    override suspend fun find(name: String): McpToolDescriptor? =
        listTools().firstOrNull { it.name == name }

    override suspend fun call(name: String, arguments: JsonObject): McpToolCallOutcome {
        // T-JOURNAL：全部工具调用计时 + 度量 + 事件流（事件可重建，不散落 logcat）。
        val startedAt = System.currentTimeMillis()
        val outcome = try {
            when (name) {
                TOOL_RUN -> run(arguments)
                TOOL_LIST_SESSIONS -> listSessions()
                TOOL_SEND_SIGNAL -> sendSignal(arguments)
                TOOL_RESIZE -> resize(arguments)
                TOOL_READ_SCREEN -> readScreen(arguments)
                TOOL_WRITE -> writeInput(arguments)
                TOOL_GATE_STATUS -> gateStatus(arguments)
                TOOL_EVENTS -> events(arguments)
                else -> McpToolCallOutcome(text = "unknown tool: $name", isError = true)
            }
        } catch (t: Throwable) {
            // Runtime 失败（TerminalError typed / legacy 字符串异常）→ 结构化错误面。
            toolError(t)
        }
        val durationMs = System.currentTimeMillis() - startedAt
        metrics?.record(name, durationMs, outcome.isError)
        journal?.record(
            "tool_call",
            "tool" to name,
            "duration_ms" to durationMs,
            "error" to outcome.isError,
        )
        return outcome
    }

    // ─── run ───

    private suspend fun run(arguments: JsonObject): McpToolCallOutcome {
        val command = arguments.stringArg("command")
            ?: return invalidParams("run requires string param: command")
        val sessionId = arguments.longArg("session_id") ?: sharedSession()
        val timeoutMs = arguments.longArg("timeout_ms") ?: DEFAULT_TIMEOUT_MS
        val maxOutputBytes = (arguments.intArg("max_output_bytes") ?: DEFAULT_MAX_OUTPUT_BYTES)
            .coerceIn(MIN_OUTPUT_BYTES, MAX_OUTPUT_BYTES_CAP)

        // T-GATE：写入前门控 —— 命令进入 PTY 前评估（进 PTY 后无法拦截）。
        journal?.record("command_proposed", "session" to sessionId, "tool" to "run", "cmd" to command)
        val gateDecision = securityGate.evaluate(command, sessionId)
        when (gateDecision) {
            is SecurityGate.GateDecision.Blocked -> {
                journal?.record(
                    "gate_decision",
                    "session" to sessionId, "decision" to "block",
                    "risk" to gateDecision.assessment.level.name, "reason" to gateDecision.assessment.reason,
                )
                val structured = buildJsonObject {
                    put("ok", false)
                    put("status", "blocked")
                    put("risk", gateDecision.assessment.level.name)
                    put("reason", gateDecision.assessment.reason)
                }
                return McpToolCallOutcome(text = structured.toString(), structured = structured, isError = true)
            }
            is SecurityGate.GateDecision.RequireConfirmation -> {
                journal?.record(
                    "gate_decision",
                    "session" to sessionId, "decision" to "require_confirm",
                    "confirm_id" to gateDecision.confirmId,
                    "risk" to gateDecision.assessment.level.name, "reason" to gateDecision.assessment.reason,
                )
                val structured = buildJsonObject {
                    put("ok", false)
                    put("status", "confirmation_required")
                    put("confirm_id", gateDecision.confirmId)
                    put("risk", gateDecision.assessment.level.name)
                    put("reason", gateDecision.assessment.reason)
                    put("expires_at_ms", gateDecision.expiresAtMs)
                    put("hint", "human approval pending — retry the same command after approval")
                }
                return McpToolCallOutcome(text = structured.toString(), structured = structured, isError = true)
            }
            is SecurityGate.GateDecision.Allowed -> {
                journal?.record(
                    "gate_decision",
                    "session" to sessionId, "decision" to "allow",
                    "risk" to gateDecision.assessment.level.name, "reason" to gateDecision.assessment.reason,
                )
            }
        }

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
        // T-JOURNAL：执行结果入流（proposed → gate_decision → executed 全链可重放）。
        journal?.record(
            "command_executed",
            "session" to sessionId, "cmd" to command,
            "exit_code" to exitCode, "timed_out" to timedOut,
            "truncated" to (limited.truncated || (observed?.truncated ?: false)),
            "duration_ms" to (System.currentTimeMillis() - startedAt).toInt(),
        )
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

    // ─── read_screen ───

    /**
     * 语义化读屏（terminal 十连 PR 3/10）：live_screen + 光标 + alt screen +
     * cwd（引擎 OSC 7 直解 / reducer 侧车回填）+ 最近退出码 + stable scrollback。
     */
    private suspend fun readScreen(arguments: JsonObject): McpToolCallOutcome {
        val sessionId = arguments.longArg("session_id") ?: sharedSession()
        val includeScrollback = arguments.boolArg("include_scrollback") ?: false
        val scrollbackLines = (arguments.intArg("scrollback_lines") ?: DEFAULT_SCROLLBACK_LINES)
            .coerceIn(1, MAX_SCROLLBACK_LINES)
        val observed = runtime.observe(
            sessionId,
            mode = TerminalRuntime.ObserveMode.SCREEN,
            scrollbackLines = if (includeScrollback) scrollbackLines else 0,
        ).getOrElse { return toolError(it) }
        val screen = observed.screen
            ?: return toolError(RuntimeException("session has no screen state"))
        val sessionSemantic = runtime.snapshot(TerminalRuntime.SnapshotMode.SESSIONS)
            .getOrNull()?.sessions?.firstOrNull { it.session.id == sessionId }
        val liveLines = (screen.renderedText ?: "").ifEmpty { "" }.split('\n')
        val structured = buildJsonObject {
            put("session_id", sessionId)
            put("cwd", screen.guestCwd ?: sessionSemantic?.session?.cwd)
            put("cursor", buildJsonObject {
                put("row", screen.cursorRow)
                put("col", screen.cursorCol)
            })
            put("rows", screen.rows)
            put("cols", screen.cols)
            put("alternate_screen", screen.alternateScreen)
            put("cursor_visible", screen.cursorVisible)
            put("title", screen.title)
            put("live_screen", JsonArray(liveLines.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("scrollback_lines_total", screen.scrollbackLineCount ?: 0)
            if (includeScrollback) {
                put(
                    "scrollback",
                    JsonArray((observed.scrollbackTail ?: emptyList()).map { kotlinx.serialization.json.JsonPrimitive(it) }),
                )
            }
            putNullableInt("last_exit_code", sessionSemantic?.session?.lastExitCode)
            put("has_foreground_process", sessionSemantic?.foregroundJob != null)
        }
        return McpToolCallOutcome(text = "ok", structured = structured)
    }

    // ─── write ───

    /** 交互输入：run 超时 → read_screen 看到 Password:/[Y/n] → 写入。 */
    private suspend fun writeInput(arguments: JsonObject): McpToolCallOutcome {
        val text = arguments.stringArg("text")
            ?: return invalidParams("write requires string param: text")
        val kind = when (val k = arguments.stringArg("kind") ?: "line") {
            "line" -> TerminalRuntime.WriteKind.LINE
            "raw" -> TerminalRuntime.WriteKind.RAW
            else -> return invalidParams("write kind must be line or raw (got $k)")
        }
        val sessionId = arguments.longArg("session_id") ?: sharedSession()
        val result = runtime.write(
            sessionId,
            owner = InputOwner.AGENT,
            kind = kind,
            text = text,
        ).getOrElse { return toolError(it) }
        val structured = buildJsonObject {
            put("session_id", sessionId)
            put("written", result.written)
            put("bytes_written", result.bytesWritten)
        }
        return McpToolCallOutcome(text = "ok", structured = structured)
    }

    // ─── gate_status ───

    /** 门控状态查询：待确认单列表 / 单据详情（Agent 轮询人侧裁决结果）。 */
    private suspend fun gateStatus(arguments: JsonObject): McpToolCallOutcome {
        val confirmId = arguments.stringArg("confirm_id")
        val structured = if (confirmId != null) {
            val pending = securityGate.findPending(confirmId)
            buildJsonObject {
                putNullableLong("found_at", clockNow())
                if (pending != null) {
                    put("status", "pending")
                    put("confirm_id", pending.confirmId)
                    put("command", pending.command)
                    put("risk", pending.assessment.level.name)
                    put("reason", pending.assessment.reason)
                    putNullableLong("session_id", pending.sessionId)
                    put("expires_at_ms", pending.expiresAtMs)
                } else {
                    put("status", "unknown_or_expired")
                    put("confirm_id", confirmId)
                }
            }
        } else {
            buildJsonObject {
                put("pending_count", securityGate.pendingList().size)
                put("active_approvals", securityGate.activeApprovalCount())
                put(
                    "pending",
                    JsonArray(securityGate.pendingList().map { p ->
                        buildJsonObject {
                            put("confirm_id", p.confirmId)
                            put("command", p.command)
                            put("risk", p.assessment.level.name)
                            put("reason", p.assessment.reason)
                            putNullableLong("session_id", p.sessionId)
                            put("expires_at_ms", p.expiresAtMs)
                        }
                    }),
                )
            }
        }
        return McpToolCallOutcome(text = "ok", structured = structured)
    }

    /** 事件流时钟源（PR 6/10 接 EventJournal 后与事件时间戳同源）。 */
    private fun clockNow(): Long = System.currentTimeMillis()

    // ─── events ───

    /**
     * 事件流查询（T-JOURNAL）：tail N + event_type 过滤 + since_seq 游标翻页；
     * 附每工具 p50/p95 度量与 latest_seq（下次翻页起点）。
     */
    private suspend fun events(arguments: JsonObject): McpToolCallOutcome {
        val journal = journal
            ?: return toolError(RuntimeException("event journal not configured on this server"))
        val limit = (arguments.intArg("limit") ?: 50).coerceIn(1, 500)
        val eventType = arguments.stringArg("event_type")
        val sinceSeq = arguments.longArg("since_seq") ?: 0L
        val entries = journal.tail(limit, eventType, sinceSeq)
        val metricsSnapshot = metrics?.snapshot() ?: emptyMap()
        val structured = buildJsonObject {
            put("events", JsonArray(entries.toList()))
            put("latest_seq", journal.latestSeq())
            putJsonObject("metrics") {
                metricsSnapshot.forEach { (tool, summary) ->
                    putJsonObject(tool) {
                        put("calls", summary.calls)
                        put("errors", summary.errors)
                        put("p50_ms", summary.p50Ms)
                        put("p95_ms", summary.p95Ms)
                    }
                }
            }
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
        sharedSessionMutex.withLock {
            val existing = sharedSessionId
            if (existing != null && isSessionUsable(existing)) return existing
            val created = runtime.create(rows = SHARED_SESSION_ROWS, cols = SHARED_SESSION_COLS)
                .getOrElse { throw it }
            sharedSessionId = created.sessionId
            return created.sessionId
        }
        // withLock 的非局部 return 已覆盖全路径，此处不可达
        error("unreachable")
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
        const val TOOL_READ_SCREEN = "read_screen"
        const val TOOL_WRITE = "write"
        const val TOOL_GATE_STATUS = "gate_status"
        const val TOOL_EVENTS = "events"

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

        /** read_screen 默认滚回尾部行数（请求 include_scrollback 时生效）。 */
        const val DEFAULT_SCROLLBACK_LINES = 50
        const val MAX_SCROLLBACK_LINES = 500
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
