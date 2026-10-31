<!-- pr: terminal-mcp-control-center -->

- MCP 控制中心（终端页顶栏 Hub 入口）：状态卡（Running 端口/Disabled/Failed 原因）+ 总开关；端点与令牌卡（端点一键复制 / 令牌显隐 / 复制 / 再生成（服务器每请求实时读 store，无需重启））；端口编辑与应用（保存 + 重启监听）
- 接入引导卡：本机直连提示 + adb reverse USB 转发命令 + Claude Code claude mcp add 完整命令 + RikkaHub Streamable HTTP 配置说明 —— 三客户端一键复制
- 门控待确认队列（PR 5/10 的人侧入口补全）：Dangerous 命令列表（命令 + 风险理由）+「准许一次」/「拒绝」按钮 —— consume SecurityGate 单例的 pendingFlow（与 MCP 工具面同实例）
- DI：SecurityGate 提升为 @Singleton @Provides（控制中心与 TerminalRuntimeTools 共用同一门控实例）
- TerminalViewModel 注入 McpServerManager/McpConfigStore/SecurityGate（控制中心的数据与操作面）
- 双语字符串 21 键（i18n 镜像门禁通过）
