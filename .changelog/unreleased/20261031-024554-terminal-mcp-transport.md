<!-- pr: terminal-mcp-transport -->

- 新模块 platform:terminal-mcp：Theocracy 终端升级为 Agent 可调用终端的第一块 —— MCP Streamable HTTP 传输基座（仅绑定 127.0.0.1，NanoHTTPD thread-per-connection）
- 协议合规：POST /mcp 纯 JSON 响应（spec 2025-06-18 允许的最小路线）/ notification 202 / GET·DELETE 405 / 协议版本三值协商（2024-11-05、2025-03-26、2025-06-18）/ Mcp-Session-Id 分配 / Mcp-Protocol-Version 非法拒绝
- JSON-RPC 2.0 完整错误码面（-32700、-32600、-32601、-32602、-32603），tools/call 双通道响应（structuredContent 镜像进 text block）
- 安全基线：Bearer 令牌鉴权（256 位首启自动生成 + 常量时间比较 + ?token= 查询兜底）+ Origin 白名单校验（防 DNS rebinding）+ OPTIONS CORS 预检
- App 接线：ApexApp 启动即拉起 McpServerManager（幂等 / enabled 可关 / 失败不阻断宿主）；工具面以 NoToolProvider 空实现起步，PR 2/10 接入 TerminalRuntime
- JVM 单测 40+ 项：握手 / 版本协商 / 鉴权 / Origin / 错误码 / 方法路由 / 生命周期（HttpURLConnection 直打真实服务器实例，无 mock 框架）
