<!-- pr: terminal-process-registry -->

- 托管进程注册表 MCP 化（process 域 + 四工具）：长任务（npm run dev / 日志 tail / 编译）派生到独立会话（非共享），立即返回 handle —— Agent 调用链不再被长任务阻塞
- process_spawn：与 run 同门控（写入前评估，Dangerous 人工确认 / Critical 硬拒），background job 派生，注册表簿记（业务名 / jobId / 起始游标）
- process_read：from_cursor 游标非阻塞增量 poll（默认起点 = spawn 游标；退出进程保留可读 —— 最终输出与退出码可收割）
- process_list：注册表 × runtime 快照对账（alive / state / exit_code，会话消失如实上报 GONE）
- process_kill：复用 TerminalRuntime.cancel 三级序列（SIGTERM → 宽限 → SIGKILL），shell 会话存活
- 工具面 12 个（run / list_sessions / send_signal / resize / read_screen / write / gate_status / events / process_spawn / process_read / process_list / process_kill）
- 事件流新增 process_spawned / process_killed 类型；测试 5 项（spawn 独立会话与注册 / 门控拦截 / 游标增量 / 状态对账 / kill 三级序列调用）
