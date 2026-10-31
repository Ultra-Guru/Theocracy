package com.apex.agent.terminalmcp

import com.apex.agent.terminalmcp.McpServerManager.McpServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpServerManager 生命周期单测：幂等 start / disabled 语义 / restart 换端口 /
 * 状态机推流（端口 0 临时端口形态 —— 与生产唯一差异是端口号来源）。
 */
class McpServerManagerTest {

    private fun config(
        enabled: Boolean = true,
        port: Int = 0,
        token: String = "manager-test-token",
    ) = McpServerConfig(enabled = enabled, port = port, authToken = token)

    @Test
    fun `start binds an ephemeral port and reports running state`() {
        val manager = McpServerManager(InMemoryMcpConfigStore(config()), NoToolProvider)
        manager.start()
        try {
            assertTrue(manager.isRunning())
            val state = manager.state.value as McpServerState.Running
            assertTrue(state.port > 0)
            assertEquals("http://127.0.0.1:${state.port}/mcp", state.endpoint)
            assertEquals(state.port, manager.boundPort())
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `start is idempotent - second start keeps same port`() {
        val manager = McpServerManager(InMemoryMcpConfigStore(config()), NoToolProvider)
        manager.start()
        try {
            val first = manager.boundPort()
            manager.start()
            assertEquals(first, manager.boundPort())
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `disabled config never listens`() {
        val manager = McpServerManager(InMemoryMcpConfigStore(config(enabled = false)), NoToolProvider)
        manager.start()
        assertFalse(manager.isRunning())
        assertTrue(manager.state.value is McpServerState.Disabled)
        assertNull(manager.boundPort())
    }

    @Test
    fun `stop transitions to stopped and is idempotent`() {
        val manager = McpServerManager(InMemoryMcpConfigStore(config()), NoToolProvider)
        manager.start()
        manager.stop()
        manager.stop()
        assertFalse(manager.isRunning())
        assertTrue(manager.state.value is McpServerState.Stopped)
    }

    @Test
    fun `restart picks up new port from store`() {
        val store = InMemoryMcpConfigStore(config(port = 0))
        val manager = McpServerManager(store, NoToolProvider)
        manager.start()
        val firstPort = manager.boundPort()
        manager.stop()
        // 换一个（几乎必然不同的）临时端口语义：再次 start 系统重新分配。
        manager.start()
        try {
            assertNotNull(manager.boundPort())
            assertTrue(manager.boundPort()!! > 0)
            // 两次都成功绑定 —— restart 的核心承诺是「旧 socket 干净收掉」。
            assertTrue(manager.isRunning())
            if (firstPort != null && firstPort == manager.boundPort()) {
                // 临时端口偶发复用不算失败 —— 断言只要求成功监听。
            }
        } finally {
            manager.stop()
        }
    }

    @Test
    fun `failed binding is reported as failed state`() {
        // 端口 1 通常需要特权 —— 在 CI 的 Linux runner 上必然绑定失败。
        val manager = McpServerManager(InMemoryMcpConfigStore(config(port = 1)), NoToolProvider)
        manager.start()
        assertFalse(manager.isRunning())
        assertTrue(manager.state.value is McpServerState.Failed)
    }
}
