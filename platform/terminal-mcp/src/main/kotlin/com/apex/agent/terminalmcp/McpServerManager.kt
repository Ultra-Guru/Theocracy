package com.apex.agent.terminalmcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * MCP 服务器生命周期管理器：start / stop / restart + 状态推流。
 *
 * 生命周期语义：
 * - [start] 幂等 —— 已运行时直接返回；配置 disabled 时置 [McpServerState.Disabled]
 *   且不监听任何端口；
 * - [restart] 供配置变更（端口 / 开关）后调用 —— stop + start，端口 0 的
 *   测试形态下每次拿到新临时端口；
 * - 令牌变更不需要 restart —— 服务器每请求实时读 store（见
 *   [McpHttpServer] 的 tokenProvider）。
 *
 * 状态经 [state] StateFlow 暴露（PR 9/10 的 MCP 控制中心 UI 消费同一流）。
 */
class McpServerManager(
    private val store: McpConfigStore,
    private val tools: McpToolProvider,
) {

    /** 服务器运行状态（UI / 诊断可观察）。 */
    sealed class McpServerState {
        /** 未启动（初始态 / 显式 stop 后）。 */
        data object Stopped : McpServerState()

        /** 配置关闭 —— start 被调用但没有监听端口。 */
        data object Disabled : McpServerState()

        /** 运行中：[port] 为实际监听端口（配置端口 0 时是系统分配值）。 */
        data class Running(val port: Int, val endpoint: String) : McpServerState()

        /** 启动失败（端口占用等）—— reason 给 UI 展示。 */
        data class Failed(val reason: String) : McpServerState()
    }

    private val lock = Any()
    private var server: McpHttpServer? = null

    private val _state = MutableStateFlow<McpServerState>(McpServerState.Stopped)
    val state: StateFlow<McpServerState> = _state.asStateFlow()

    /** 幂等启动；配置 disabled 时不监听。线程安全（app 启动线 / 设置页调用）。 */
    fun start() {
        synchronized(lock) {
            if (server?.isAlive == true) return
            val config = store.load()
            if (!config.enabled) {
                _state.value = McpServerState.Disabled
                return
            }
            try {
                val instance = McpHttpServer(
                    port = config.port,
                    tokenProvider = { store.load().authToken },
                    dispatcher = McpDispatcher(
                        McpDispatcher.ServerInfo(
                            name = config.serverName,
                            version = config.serverVersion,
                        ),
                        tools,
                    ),
                )
                // 20s socket read timeout：对 keep-alive 空闲连接友好（OkHttp
                // 连接池复用），daemon 线程不阻 JVM/进程退出。
                instance.start(20_000, true)
                server = instance
                _state.value = McpServerState.Running(
                    port = instance.listeningPort,
                    endpoint = "http://127.0.0.1:${instance.listeningPort}/mcp",
                )
            } catch (t: Throwable) {
                // 折叠为可观察状态而非异常上抛：启动失败不该炸宿主进程
                // （app onCreate 的 runCatching 只做第二道防线）。
                server = null
                _state.value = McpServerState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** 幂等停止。 */
    fun stop() {
        synchronized(lock) {
            server?.let { instance ->
                try {
                    instance.stop()
                } catch (e: Exception) {
                    // stop 异常也吞为状态推进 —— NanoHTTPD 已死 socket 的
                    // close 异常无恢复动作可做，留状态即可。
                }
            }
            server = null
            _state.value = McpServerState.Stopped
        }
    }

    /** 配置变更后重启（端口 / 开关切换）。 */
    fun restart() {
        stop()
        start()
    }

    /** 实际监听端口（未运行时 null；端口 0 测试形态下为系统分配值）。 */
    fun boundPort(): Int? = synchronized(lock) { server?.listeningPort }

    /** 是否有存活监听。 */
    fun isRunning(): Boolean = synchronized(lock) { server?.isAlive == true }
}
