package com.apex.agent.di

import android.content.Context
import com.apex.agent.platform.terminal.runtime.TerminalRuntime
import com.apex.agent.terminalmcp.McpAuth
import com.apex.agent.terminalmcp.McpConfigStore
import com.apex.agent.terminalmcp.McpServerConfig
import com.apex.agent.terminalmcp.McpServerManager
import com.apex.agent.terminalmcp.event.EventJournal
import com.apex.agent.terminalmcp.event.ToolMetrics
import com.apex.agent.terminalmcp.security.SecurityGate
import com.apex.agent.terminalmcp.tools.TerminalRuntimeTools
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Theocracy MCP 服务器接线（terminal 十连 PR 1/10 —— 传输基座）。
 *
 * - 配置持久化走 SharedPreferences "apex_terminal_mcp"（独立于 "apex_terminal"
 *   终端设置文件 —— MCP 配置的生命周期与终端行为设置无关，避免互相踩键）；
 * - Bearer 令牌首启自动生成（256 位），此后稳定持久 —— Agent 客户端配置
 *   一次长期有效，PR 9/10 的控制中心提供再生成入口；
 * - 工具面暂为 [NoToolProvider] 空实现（PR 2/10 接入 TerminalRuntime 适配器：
 *   run / list_sessions / send_signal / resize ...）；
 * - [McpServerManager.start] 由 ApexApp.onCreate 调用（幂等，enabled=false
 *   时不监听任何端口）。
 */
@Module
@InstallIn(SingletonComponent::class)
object TerminalMcpModule {

    private const val PREFS_NAME = "apex_terminal_mcp"
    private const val KEY_ENABLED = "mcp_enabled"
    private const val KEY_PORT = "mcp_port"
    private const val KEY_TOKEN = "mcp_auth_token"

    @Provides
    @Singleton
    fun provideMcpConfigStore(@ApplicationContext context: Context): McpConfigStore {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return object : McpConfigStore {
            override fun load(): McpServerConfig {
                val token = prefs.getString(KEY_TOKEN, null)
                    ?: McpAuth.generateToken().also { generated ->
                        prefs.edit().putString(KEY_TOKEN, generated).apply()
                    }
                return McpServerConfig(
                    enabled = prefs.getBoolean(KEY_ENABLED, true),
                    port = prefs.getInt(KEY_PORT, McpServerConfig.DEFAULT_PORT),
                    authToken = token,
                )
            }

            override fun save(config: McpServerConfig) {
                prefs.edit()
                    .putBoolean(KEY_ENABLED, config.enabled)
                    .putInt(KEY_PORT, config.port)
                    .putString(KEY_TOKEN, config.authToken)
                    .apply()
            }
        }
    }

    @Provides
    @Singleton
    fun provideMcpServerManager(
        @ApplicationContext context: Context,
        store: McpConfigStore,
        runtime: TerminalRuntime,
    ): McpServerManager {
        // T-GATE（PR 5/10）：写入前门控单例 —— run/process_spawn 共用；人侧
        // 确认 UI（PR 9/10 控制中心）消费同一实例的 pendingFlow。
        val gate = SecurityGate()
        // T-JOURNAL（PR 6/10）：JSONL 事件流落盘 filesDir/logs（2MB 双文件轮转）
        // + 每工具 p50/p95 度量窗口。
        val journal = EventJournal(java.io.File(context.filesDir, "logs"))
        val metrics = ToolMetrics()
        return McpServerManager(store, TerminalRuntimeTools(runtime, gate, journal, metrics))
    }
}
