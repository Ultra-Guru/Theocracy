<!-- pr: terminal-event-journal -->

- EventJournal JSONL 事件流（terminal-mcp event 域）：MCP 面全部关键动作追加写 filesDir/logs/terminal-events.jsonl —— 命令发起时间、门控决策、执行结果、截断标记全部可重建，不再散落 logcat 靠 grep 拼凑（termux-mcp events.jsonl 范式）
- 事件类型：command_proposed（cmd/session/tool/risk 前置）→ gate_decision（decision: allow/require_confirm/block + reason）→ command_executed（exit_code/timed_out/truncated/duration_ms）→ tool_call（全工具计时与成败）—— 一次 run 的完整决策链可逐行重放
- 2MB 双文件轮转（.1 覆盖最旧，上限 4MB）；seq 跨轮转与重启单调连续（构造时从尾行恢复）
- ToolMetrics 内存度量窗口（每工具最近 100 次）：p50/p95 延迟 + 调用数 + 错误率（termux-mcp /metrics 范式）
- events 工具（第 8 个）：tail N（默认 50/上限 500）+ event_type 过滤 + since_seq 游标翻页 + latest_seq 返回 + metrics 快照
- DI：EventJournal 落盘 filesDir/logs；TerminalRuntimeTools 四参数构造（runtime/gate/journal/metrics，后两项可空保持既有测试形态）
- 单测：EventJournal 7 项（字段完整性/seq 重启连续/轮转不丢/过滤/游标/limit 切片/度量）+ 工具面 3 项（重放链/过滤翻页/未配置降级）
