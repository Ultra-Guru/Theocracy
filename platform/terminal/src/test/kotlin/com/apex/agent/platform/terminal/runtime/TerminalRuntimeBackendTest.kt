package com.apex.agent.platform.terminal.runtime

import com.apex.agent.platform.terminal.pty.FakeNativePty
import com.apex.agent.platform.terminal.policy.TerminalPolicyImpl
import com.apex.agent.platform.terminal.session.SessionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * T73 — TerminalRuntime ↔ ExecutionBackend 正式接线的核心契约测试。
 *
 * Theocracy 收束后（Ubuntu/PRoot 栈移除）证明（JVM/FakeNativePty 层）：
 *   1. create() 统一经 ExecutionBackendRegistry 路由：local 默认路径与
 *      golden 行为一致（argv/env 逐字节）；
 *   2. 失败模式可行动：BackendNotFound / BackendFailed；
 *   3. backends() 能力发现：READY / FAILED 两态如实上报；
 *   4. 后端元数据进 TerminalSession.backend（持久化契约在
 *      SessionMetadataStoreBackendTest）；
 *   5. 接缝仍开放：任意新后端注册进 Registry 即被同一链路路由
 *      （StubBackend 以 "stub-alt" id 验证，不预设 Linux 语义）。
 */
class TerminalRuntimeBackendTest {

    /** 接缝探针后端：任意 id / 任意 availability —— 证明 Registry 路由是开放接缝。 */
    private class StubBackend(
        private val availability: BackendAvailability = BackendAvailability.Ready
    ) : ExecutionBackend {
        override val id: String = "stub-alt"
        override val runtimeType: BackendRuntimeType = BackendRuntimeType.LINUX
        var lastRequest: SessionSpawnRequest? = null

        override suspend fun availability(): BackendAvailability = availability

        override suspend fun prepare(request: SessionSpawnRequest): Result<SpawnSpec> {
            lastRequest = request
            return Result.success(
                SpawnSpec(
                    argv = listOf("/system/bin/sh", "-i"),
                    env = mapOf("PATH" to "/system/bin"),
                    cwd = "/data/local/tmp",
                    cwdIsGuestPath = false,
                    metadata = BackendSessionMetadata(
                        backendId = id,
                        guestCwd = null
                    )
                )
            )
        }
    }

    private fun newRuntime(
        pty: FakeNativePty = FakeNativePty(),
        extra: ExecutionBackend = StubBackend()
    ): Pair<TerminalRuntimeImpl, FakeNativePty> {
        val rt = TerminalRuntimeImpl(
            native = pty,
            policy = TerminalPolicyImpl(),
            backendRegistry = ExecutionBackendRegistry.of(LocalShellBackend(), extra)
        )
        return rt to pty
    }

    // ─── 1. local 默认路由（golden 行为不回归） ───

    @Test
    fun `default create routes through local backend with golden argv`() = runBlocking {
        val (rt, pty) = newRuntime()
        val r = rt.create(shell = "/system/bin/sh", cwd = "/sdcard").getOrThrow()
        assertEquals("local", r.backendId)
        assertEquals("ANDROID_LOCAL", r.runtimeType)
        assertEquals("/system/bin/sh", r.shell)
        assertEquals("/sdcard", r.cwd)

        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        assertEquals(listOf("/system/bin/sh", "-i"), pty.argvOf(nativeId))
        assertEquals("/system/bin/sh", pty.spawnEnvOf(nativeId)["SHELL"])
    }

    @Test
    fun `local create passes explicit env through backend merge`() = runBlocking {
        val (rt, pty) = newRuntime()
        val r = rt.create(env = mapOf("FOO" to "bar", "TERM" to "vt100")).getOrThrow()
        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        val env = pty.spawnEnvOf(nativeId)
        // 显式变量生效（T73 修复：此前 LocalShellBackend 丢弃 request.env）
        assertEquals("bar", env["FOO"])
        assertEquals("vt100", env["TERM"])   // 显式覆盖默认
        assertEquals("shell", env["USER"])   // 默认仍在
    }

    // ─── 2. 接缝路由（新后端注册即生效，不预设 Linux 语义） ───

    @Test
    fun `registered non-default backend is routed by id`() = runBlocking {
        val (rt, pty) = newRuntime()
        val r = rt.create(backendId = "stub-alt").getOrThrow()

        assertEquals("stub-alt", r.backendId)
        assertEquals("LINUX", r.runtimeType)

        val nativeId = rt.sessionManager.assembly(r.sessionId)!!.nativeSessionId
        assertEquals("/system/bin/sh", pty.argvOf(nativeId)[0])

        val session = rt.sessionManager.get(r.sessionId) ?: error("session not found")
        assertEquals("stub-alt", session.backend?.backendId)
    }

    @Test
    fun `local session carries local backend metadata`() = runBlocking {
        val (rt, _) = newRuntime()
        val r = rt.create().getOrThrow()
        val session = rt.sessionManager.get(r.sessionId) ?: error("session not found")
        // 统一路由后本地会话也携带 backend 元数据（backendId=local）
        assertEquals("local", session.backend?.backendId)
    }

    // ─── 3. 失败模式（可行动错误） ───

    @Test
    fun `unknown backend fails with BackendNotFound listing available`() = runBlocking {
        val (rt, _) = newRuntime()
        val err = rt.create(backendId = "linux-debian").exceptionOrNull()
        assertNotNull(err)
        val msg = err!!.message ?: ""
        assertTrue("BackendNotFound: $msg", msg.contains("BackendNotFound"))
        assertTrue("lists available backends: $msg", msg.contains("local") && msg.contains("stub-alt"))
    }

    @Test
    fun `failed backend reports reason`() = runBlocking {
        val stub = StubBackend(availability = BackendAvailability.Failed("SpawnError:BINARY_NOT_FOUND"))
        val (rt, _) = newRuntime(extra = stub)
        val err = rt.create(backendId = "stub-alt").exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("BackendFailed"))
        assertTrue(err.message!!.contains("BINARY_NOT_FOUND"))
    }

    // ─── 4. backends() 能力发现 ───

    @Test
    fun `backends reports local READY honestly`() = runBlocking {
        val (rt, _) = newRuntime()
        val list = rt.backends()
        assertEquals(2, list.size)

        val local = list.first { it.id == "local" }
        assertTrue(local.available)
        assertEquals("READY", local.state)
        assertEquals("ANDROID_LOCAL", local.runtimeType)
    }

    @Test
    fun `backends reports failed backend with reason in detail`() = runBlocking {
        val stub = StubBackend(availability = BackendAvailability.Failed("SpawnError:BINARY_NOT_FOUND"))
        val (rt, _) = newRuntime(extra = stub)
        val other = rt.backends().first { it.id == "stub-alt" }
        assertFalse(other.available)
        assertEquals("FAILED", other.state)
        assertTrue(other.detail!!.contains("BINARY_NOT_FOUND"))
    }

    // ─── 5. 后端会话上的常规操作不受影响 ───

    @Test
    fun `run and observe work on a non-default backend session`() = runBlocking {
        val (rt, _) = newRuntime()
        val r = rt.create(backendId = "stub-alt").getOrThrow()
        assertEquals(SessionState.READY.name, r.state)

        // run 走同一条 JobManager 路径（backend 无关）
        val job = rt.run(r.sessionId, "echo hello", com.apex.agent.platform.terminal.io.InputOwner.AGENT)
        assertTrue(job.isSuccess)
        assertTrue(job.getOrThrow().jobId > 0)

        val obs = rt.observe(r.sessionId, com.apex.agent.platform.terminal.runtime.TerminalRuntime.ObserveMode.RAW, 0, 65536)
        assertTrue(obs.isSuccess)

        val closed = rt.close(r.sessionId)
        assertTrue(closed.isSuccess)
    }

    // ─── 6. T75: workspace 接缝字段（local 后端显式拒绝非空值） ───

    @Test
    fun `local create with workspaceId is rejected as InvalidInput`() = runBlocking {
        val (rt, _) = newRuntime()
        val err = rt.create(backendId = "local", workspaceId = "alpha").exceptionOrNull()
        assertNotNull(err)
        assertTrue(err!!.message!!.contains("TerminalError:InvalidInput"))
        assertTrue(err.message!!.contains("workspaceId"))
    }
}
