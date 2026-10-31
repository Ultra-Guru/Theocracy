<!-- pr: terminal-mcp-core-tools -->

- MCP 核心工具面四件套接入 TerminalRuntime 冻结门面：run（阻塞执行 + WaitEngine 事件等待 + OSC 633 真实退出码 + 20KB head/tail 截断）/ list_sessions（会话簿：状态、cwd、几何、最近退出码、前后台 job）/ send_signal（前台作业组信号，Ctrl-C 语义：打断当前命令、shell 存活）/ resize（TIOCSWINSZ 同步）
- 共享会话语义：session_id 缺省时 find-or-create 默认会话（40x120 宽幅几何减少读屏折行），cd / 环境变量 / shell 别名跨命令连续；会话终态后自动重建
- run 超时不杀进程：timed_out=true + partial 输出返回，Agent 可 send_signal 打断或后续 process 工具（PR 7/10）继续收割
- OSC 633 真机双帧 bug 修复：wrapCommand 的 printf 只传单参数（$?）—— POSIX printf 会对剩余参数复用格式串，旧实现（jobId 与 $? 双参数）在真机连发两个 marker，首帧以 jobId 冒充退出码提前终结 job，真实退出码反被丢弃；CI 的 FakeNativePty 未模拟格式复用语义故从未暴露
- 工具面错误结构化：TerminalError typed code / recoverable 全量映射到 MCP isError 结果
- JVM 单测 20 项：手写 FakeRuntime（脚本化 run/wait/observe + 调用记录），覆盖共享会话复用、终态重建、超时、会话消亡、截断传播、错误映射
