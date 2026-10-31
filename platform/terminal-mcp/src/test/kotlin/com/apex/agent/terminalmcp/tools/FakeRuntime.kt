package com.apex.agent.terminalmcp.tools

import com.apex.agent.platform.TerminalWait.WaitCondition
import com.apex.agent.platform.TerminalWait.WaitResult
import com.apex.agent.platform.terminal.events.CloseCause
import com.apex.agent.platform.terminal.events.ExitCause
import com.apex.agent.platform.terminal.events.TerminalEvent
import com.apex.agent.platform.terminal.io.InputOwner
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.platform.terminal.io.UnixSignal
import com.apex.agent.platform.terminal.job.JobState
import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.platform.terminal.screen.TerminalScreenState
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.state.InputSnapshot
import com.apex.agent.platform.terminal.state.InputState
import com.apex.agent.platform.terminal.state.JobSnapshot
import com.apex.agent.platform.terminal.state.ScreenSnapshot
import com.apex.agent.platform.terminal.state.SessionSnapshot
import com.apex.agent.platform.terminal.state.TerminalSemanticState
import com.apex.agent.terminalemulator.TerminalRenderSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * TerminalRuntime 手写 fake（无 mock 框架 —— 全仓纪律）。
 *
 * 面向 terminal-mcp 工具面的「映射语义」测试：run→wait→observe 的编排、
 * 超时/终态分支、错误映射、共享会话 find-or-create。真实 PTY 语义由
 * platform:terminal 自己的 EndToEnd/契约测试覆盖（FakeNativePty）。
 *
 * 脚本化行为：每个用例直接设置 [nextRunResult] / [nextWaitResult] /
 * [nextRawOutput]，fake 按序消费；全部调用记录在 recorded* 列表里。
 */
class FakeRuntime : TerminalRuntime {

    var nextSessionId = 100L
    var createCalls = 0
    var snapshotSessions: List<TerminalSemanticState> = emptyList()

    var nextRunResult: Result<TerminalRuntime.RunResult> = Result.success(
        TerminalRuntime.RunResult(jobId = 7L, sessionId = 100L, state = "RUNNING", startCursor = 0L, owner = InputOwner.AGENT, background = false),
    )
    var nextWaitResult: WaitResult = WaitResult.Matched(
        TerminalEvent.ProcessExited(
            id = 1, sessionId = 100, timestamp = 0, cursor = 10,
            jobId = 7, pid = 123, exitCode = 0, signal = null, cause = ExitCause.NORMAL,
        ),
    )
    var nextRawOutput: String = "hello\n"
    var nextObserveTruncated: Boolean = false
    var nextScreenState: com.apex.agent.platform.terminal.screen.TerminalScreenState? = null
    var nextScrollbackTail: List<String>? = null
    var observeError: Throwable? = null

    val recordedRuns = mutableListOf<Pair<Long, String>>()
    val recordedSignals = mutableListOf<Pair<Long, UnixSignal>>()
    val recordedResizes = mutableListOf<Triple<Long, Int, Int>>()
    val recordedWrites = mutableListOf<Triple<Long, TerminalRuntime.WriteKind, String?>>()
    val recordedCancels = mutableListOf<Pair<Long, Long>>()
    var nextCancelResult: Result<TerminalRuntime.CancelResult> = Result.success(
        TerminalRuntime.CancelResult(cancelled = true, jobId = 7L, finalState = "INTERRUPTED"),
    )
    var nextSignalResult: Result<TerminalRuntime.SignalResult> = Result.success(
        TerminalRuntime.SignalResult(sent = true, signal = UnixSignal.SIGINT, targetJobId = 7L, foregroundDelivered = true),
    )
    var nextResizeResult: Result<TerminalRuntime.ResizeResult> = Result.success(
        TerminalRuntime.ResizeResult(resized = true, rows = 24, cols = 80),
    )

    override suspend fun create(
        shell: String,
        cwd: String,
        rows: Int,
        cols: Int,
        env: Map<String, String>,
        privilege: PrivilegeLevel,
        backendId: String,
        workspaceId: String?,
    ): Result<TerminalRuntime.CreateResult> {
        createCalls++
        return Result.success(
            TerminalRuntime.CreateResult(
                sessionId = nextSessionId, pid = 1234, shell = shell, cwd = cwd,
                rows = rows, cols = cols, privilege = privilege, state = "READY",
                cursor = 0, backendId = backendId, runtimeType = "ANDROID_LOCAL",
            ),
        )
    }

    override suspend fun backends(): List<TerminalRuntime.BackendStatus> =
        listOf(TerminalRuntime.BackendStatus(id = "local", runtimeType = "ANDROID_LOCAL", available = true, state = "READY"))

    override suspend fun run(
        sessionId: Long,
        command: String,
        owner: InputOwner,
        background: Boolean,
        timeoutMs: Long,
    ): Result<TerminalRuntime.RunResult> {
        recordedRuns += sessionId to command
        return nextRunResult
    }

    override suspend fun observe(
        sessionId: Long,
        mode: TerminalRuntime.ObserveMode,
        afterCursor: Long,
        maxBytes: Int,
        maxEvents: Int,
        scrollbackLines: Int,
    ): Result<TerminalRuntime.ObserveResult> {
        observeError?.let { return Result.failure(it) }
        return Result.success(
            when (mode) {
                TerminalRuntime.ObserveMode.RAW -> TerminalRuntime.ObserveResult(
                    mode = TerminalRuntime.ObserveMode.RAW,
                    sessionId = sessionId,
                    cursor = afterCursor + 64,
                    startCursor = afterCursor,
                    endCursor = afterCursor + 64,
                    truncated = nextObserveTruncated,
                    raw = nextRawOutput,
                )
                TerminalRuntime.ObserveMode.SCREEN -> TerminalRuntime.ObserveResult(
                    mode = TerminalRuntime.ObserveMode.SCREEN,
                    sessionId = sessionId,
                    cursor = afterCursor + 64,
                    screen = nextScreenState,
                    scrollbackTail = if (scrollbackLines > 0) nextScrollbackTail else null,
                )
                else -> TerminalRuntime.ObserveResult(
                    mode = mode,
                    sessionId = sessionId,
                    cursor = afterCursor + 64,
                )
            },
        )
    }

    override suspend fun wait(
        sessionId: Long,
        condition: WaitCondition,
        timeoutMs: Long,
    ): Result<WaitResult> = Result.success(nextWaitResult)

    override suspend fun write(
        sessionId: Long,
        owner: InputOwner,
        kind: TerminalRuntime.WriteKind,
        text: String?,
        key: TerminalKey?,
        bytes: ByteArray?,
    ): Result<TerminalRuntime.WriteResult> {
        recordedWrites += Triple(sessionId, kind, text)
        return Result.success(
            TerminalRuntime.WriteResult(written = true, bytesWritten = text?.length ?: 1, cursor = 0, inputOwner = owner),
        )
    }

    override suspend fun signal(
        sessionId: Long,
        signal: UnixSignal,
        owner: InputOwner,
        jobId: Long?,
    ): Result<TerminalRuntime.SignalResult> {
        recordedSignals += sessionId to signal
        return nextSignalResult
    }

    override suspend fun signalForeground(
        sessionId: Long,
        signal: UnixSignal,
        owner: InputOwner,
        jobId: Long?,
    ): Result<TerminalRuntime.SignalResult> {
        recordedSignals += sessionId to signal
        return nextSignalResult
    }

    override suspend fun cancel(sessionId: Long, jobId: Long): Result<TerminalRuntime.CancelResult> {
        recordedCancels += sessionId to jobId
        return nextCancelResult
    }

    override suspend fun resize(sessionId: Long, rows: Int, cols: Int): Result<TerminalRuntime.ResizeResult> {
        recordedResizes += Triple(sessionId, rows, cols)
        return nextResizeResult
    }

    override suspend fun snapshot(
        mode: TerminalRuntime.SnapshotMode,
        sessionId: Long?,
        recentEvents: Int,
        recentOutputBytes: Int,
    ): Result<TerminalRuntime.SnapshotResult> = Result.success(
        TerminalRuntime.SnapshotResult(
            sessions = snapshotSessions,
            globalCursor = 0,
            recentEvents = emptyList(),
            recentOutput = "",
        ),
    )

    override suspend fun stop(sessionId: Long): Result<TerminalRuntime.StopResult> =
        Result.success(TerminalRuntime.StopResult(stopped = true, jobId = null, finalState = "EXITED"))

    override suspend fun close(sessionId: Long, force: Boolean): Result<TerminalRuntime.CloseResult> =
        Result.success(TerminalRuntime.CloseResult(closed = true, cause = "USER", finalCursor = 0))

    override suspend fun recover(): List<Long> = emptyList()

    override suspend fun recoveredSnapshot(sessionId: Long): TerminalSemanticState? = null

    override suspend fun shutdown(): Result<TerminalRuntime.ShutdownResult> =
        Result.success(TerminalRuntime.ShutdownResult(sessionsClosed = 0, jobsCancelled = 0, clean = true))

    override fun screenStateFlow(sessionId: Long): Flow<TerminalScreenState>? = null

    override fun semanticStateFlow(sessionId: Long): Flow<TerminalSemanticState>? = null

    override fun styledScreenFlow(sessionId: Long): Flow<TerminalRenderSnapshot?>? = null

    override fun terminalEventFlow(sessionId: Long, afterCursor: Long): Flow<TerminalEvent>? = null

    /** 便捷构造：单会话快照（list_sessions / 共享会话判定用）。 */
    fun withSession(
        sessionId: Long,
        state: SessionState = SessionState.READY,
        cwd: String = "/data/local/tmp",
        lastExitCode: Int? = null,
        foregroundJob: JobSnapshot? = null,
    ) {
        snapshotSessions = listOf(
            TerminalSemanticState(
                session = SessionSnapshot(
                    id = sessionId, shell = "/system/bin/sh", cwd = cwd,
                    privilege = PrivilegeLevel.NORMAL, state = state, pid = 42,
                    rows = 40, cols = 120, createdAt = 1234567L,
                    lastExitCode = lastExitCode, cursor = 999,
                ),
                process = null,
                screen = ScreenSnapshot(rows = 40, cols = 120, cursorRow = 0, cursorCol = 0, alternateScreen = false, title = null),
                input = InputSnapshot(state = InputState.NONE, control = com.apex.agent.platform.terminal.io.InputControlState.FREE),
                foregroundJob = foregroundJob,
                backgroundJobs = emptyList(),
            ),
        )
    }

    /** 便捷构造：前台 job 快照。 */
    fun foregroundJob(jobId: Long, command: String, state: JobState = JobState.RUNNING): JobSnapshot =
        JobSnapshot(
            id = jobId, sessionId = 100, command = command, owner = InputOwner.AGENT,
            background = false, state = state, exitCode = null,
            startedAt = 1_000L, finishedAt = null,
        )
}
