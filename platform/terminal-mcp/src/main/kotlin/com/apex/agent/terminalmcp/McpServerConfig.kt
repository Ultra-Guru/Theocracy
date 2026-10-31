package com.apex.agent.terminalmcp

/**
 * MCP 服务器运行配置（Streamable HTTP 传输）。
 *
 * 安全模型三要素都由本配置承载：
 * - [enabled] —— 总开关（关闭后 [McpServerManager] 不监听任何端口）；
 * - [port] —— 仅绑定 127.0.0.1（同机 Agent 客户端直连；桌面客户端经
 *   `adb reverse tcp:port tcp:port` 走 USB 隧道），绝不监听 0.0.0.0；
 * - [authToken] —— Bearer 令牌（首启自动生成 256 位随机值，常量时间比较）。
 *
 * @property serverName initialize 响应里的 serverInfo.name（客户端展示用）。
 * @property serverVersion 版本号随十连 PR 推进。
 */
data class McpServerConfig(
    val enabled: Boolean = true,
    val port: Int = DEFAULT_PORT,
    val authToken: String,
    val serverName: String = SERVER_NAME,
    val serverVersion: String = SERVER_VERSION,
) {
    init {
        require(authToken.isNotBlank()) { "authToken must not be blank" }
        // 0 = 系统分配临时端口（测试/诊断用）；生产固定端口（默认 8765）。
        require(port in 0..65535) { "port out of range: $port" }
    }

    companion object {
        /** 默认端口：避开常用开发端口（3000/8080），选 8765。 */
        const val DEFAULT_PORT = 8765

        /** initialize 响应的 serverInfo.name（协议内稳定标识，勿随品牌变动）。 */
        const val SERVER_NAME = "theocracy-terminal"

        const val SERVER_VERSION = "0.1.0"
    }
}
