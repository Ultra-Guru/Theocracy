package com.apex.agent.terminalmcp

/**
 * MCP 配置存取接口。
 *
 * 生产实现由 app 层提供（SharedPreferences，见 app 的 TerminalMcpModule）——
 * 本模块保持纯 Kotlin 可测：JVM 单测用 [InMemoryMcpConfigStore]。
 *
 * 读取语义：每次 [load] 都拿最新值（服务器在每次请求时实时取 token，
 * 令牌再生成后无需重启连接）。
 */
interface McpConfigStore {
    fun load(): McpServerConfig
    fun save(config: McpServerConfig)
}

/**
 * 内存实现（测试 / 预览用）。
 *
 * @Volatile 保证跨线程可见（服务器工作线程读、测试线程写）。
 */
class InMemoryMcpConfigStore(initial: McpServerConfig) : McpConfigStore {
    @Volatile
    private var current: McpServerConfig = initial

    override fun load(): McpServerConfig = current

    override fun save(config: McpServerConfig) {
        current = config
    }
}
