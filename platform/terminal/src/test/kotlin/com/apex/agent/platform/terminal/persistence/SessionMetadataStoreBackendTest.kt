package com.apex.agent.platform.terminal.persistence

import com.apex.agent.platform.terminal.policy.PrivilegeLevel
import com.apex.agent.platform.terminal.runtime.BackendSessionMetadata
import com.apex.agent.platform.terminal.session.SessionState
import com.apex.agent.platform.terminal.session.TerminalSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T73 — SessionRecord schema v2/v3：后端元数据持久化 + 向后兼容。
 *
 * 崩溃恢复后必须能区分"这个会话来自哪个后端"—— 否则恢复逻辑与 Agent
 * 上下文会把会话错当本地 shell（T73-audit G4）。历史 rootfsId 字段已随
 * Ubuntu/PRoot 栈剥离移除（旧记录中的未知键由 ignoreUnknownKeys 容忍）。
 */
class SessionMetadataStoreBackendTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun backendSession(): TerminalSession = TerminalSession(
        id = 42L, shell = "/system/bin/sh", initialCwd = "/sdcard", pid = 1234,
        rows = 24, cols = 80, privilege = PrivilegeLevel.NORMAL,
        state = SessionState.READY, createdAt = System.currentTimeMillis(),
        lastExitCode = null, cursor = 100L,
        backend = BackendSessionMetadata(
            backendId = "local",
            workspaceDir = "/data/user/0/app/files/workspace",
            binds = listOf("/data/user/0/app/files/workspace:/workspace"),
            guestCwd = "/sdcard"
        )
    )

    @Test
    fun `backend metadata round-trips through v3 record`() = runBlocking {
        val store = SessionMetadataStore(tmp.newFolder())
        store.save(backendSession(), emptyList(), emptyList())

        val loaded = store.load(42L)!!
        assertEquals(3, loaded.schemaVersion)
        assertEquals("local", loaded.backendId)
        assertEquals("/data/user/0/app/files/workspace", loaded.workspaceDir)
        assertEquals("/sdcard", loaded.guestCwd)
        assertEquals(1, loaded.binds.size)
    }

    @Test
    fun `local session persists with null backend fields`() = runBlocking {
        val store = SessionMetadataStore(tmp.newFolder())
        val local = TerminalSession(
            id = 7L, shell = "/system/bin/sh", initialCwd = "/sdcard", pid = 99,
            rows = 24, cols = 80, privilege = PrivilegeLevel.NORMAL,
            state = SessionState.READY, createdAt = System.currentTimeMillis(),
            lastExitCode = null, cursor = 0L,
            backend = BackendSessionMetadata(backendId = "local")
        )
        store.save(local, emptyList(), emptyList())

        val loaded = store.load(7L)!!
        assertEquals("local", loaded.backendId)
        assertTrue(loaded.binds.isEmpty())
    }

    @Test
    fun `v1 record without backend fields still loads (schema migration)`() = runBlocking {
        // 模拟 T73 之前写出的 v1 文件（无 backend 字段）—— ignoreUnknownKeys/默认值
        // 保证升级后旧记录可读，backendId=null 语义上等同 "local"。
        val dir = tmp.newFolder()
        val v1Json = """
            {"schemaVersion":1,"id":5,"shell":"/system/bin/sh","initialCwd":"/sdcard",
             "pid":11,"rows":24,"cols":80,"privilege":"NORMAL","state":"EXITED",
             "createdAt":1700000000000,"lastActivityAt":1700000000000,
             "lastExitCode":0,"cursor":55,"jobs":[],"recentEvents":[]}
        """.trimIndent()
        java.io.File(dir, "session-5.json").writeText(v1Json)

        val loaded = SessionMetadataStore(dir).load(5L)!!
        assertEquals(1, loaded.schemaVersion)
        assertNull(loaded.backendId)
        assertEquals("/system/bin/sh", loaded.shell)
        assertEquals(55L, loaded.cursor)
    }

    @Test
    fun `v2 record with legacy rootfsId key still loads (unknown-key tolerance)`() = runBlocking {
        // 模拟 Linux 栈时代写出的 v2 文件（含已移除的 rootfsId 字段）——
        // ignoreUnknownKeys 保证升级后旧记录可读，不因未知键崩溃。
        val dir = tmp.newFolder()
        val v2Json = """
            {"schemaVersion":2,"id":9,"shell":"/bin/bash","initialCwd":"/workspace",
             "pid":77,"rows":24,"cols":80,"privilege":"NORMAL","state":"READY",
             "createdAt":1700000000000,"lastActivityAt":1700000000000,
             "lastExitCode":null,"cursor":10,"jobs":[],"recentEvents":[],
             "backendId":"legacy-linux","rootfsId":"ubuntu-24.04.4-arm64",
             "workspaceDir":"/files/linux/workspace","guestCwd":"/workspace",
             "binds":["/files/linux/workspace:/workspace"]}
        """.trimIndent()
        java.io.File(dir, "session-9.json").writeText(v2Json)

        val loaded = SessionMetadataStore(dir).load(9L)!!
        assertEquals(2, loaded.schemaVersion)
        assertEquals("legacy-linux", loaded.backendId)
        assertNull(loaded.workspaceId)   // v2 无此字段 → 默认 null
        assertEquals("/workspace", loaded.guestCwd)
    }
}
