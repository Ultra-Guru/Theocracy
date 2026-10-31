# Theocracy Terminal — MCP 服务器接入指南

> Android 最强 Agent 可调用终端的交付文档（terminal 十连 PR 10/10）。
> 架构：**Native PTY + MCP 原生 + 写入前门控** —— Kotlin / Jetpack Compose / NDK。

## 1. 这是什么

Theocracy 终端内置一个标准 **MCP（Model Context Protocol）服务器**，把持久的
Android 本地 shell（mksh PTY，前台服务保活）暴露给任意外部 Agent 客户端：
**Claude Code、Cursor、RikkaHub** —— 无需为 Theocracy 单独写适配器。

```
┌─────────────────────────────────────────────────────┐
│  Agent 客户端 (Claude Code / Cursor / RikkaHub)      │
│            │ MCP over Streamable HTTP                │
├────────────▼─────────────────────────────────────────┤
│  McpServer 层（12 工具：run · read_screen · write ·  │
│    send_signal · list_sessions · resize ·            │
│    gate_status · events · process_spawn/read/list/kill）│
├────────────▼─────────────────────────────────────────┤
│  SecurityGate（写入 PTY 之前，本地确定性裁定）          │
│  ReadOnly / Mutating / Dangerous / Critical          │
├────────────▼─────────────────────────────────────────┤
│  TerminalRuntime（冻结门面：PTY 会话归前台服务持有）    │
├────────────▼─────────────────────────────────────────┤
│  Native PTY (JNI libapex_terminal.so) + VT 引擎       │
├────────────▼─────────────────────────────────────────┤
│  终端显示层（VT 视图 = Termux 级 Canvas 渲染）         │
└─────────────────────────────────────────────────────┘
```

核心约束：PTY 会话归属 ForegroundService —— **UI 退出或旋转不杀会话**，Agent
可在后台连续执行；人类可随时附着（VT 视图）与 Agent **人机共视**（同一份 VT 态）。

## 2. 接入

### 2.1 服务端信息

| 项 | 值 |
|---|---|
| 端点 | `http://127.0.0.1:8765/mcp`（Streamable HTTP）|
| 鉴权 | `Authorization: Bearer <token>`（或 `?token=` 查询参数）|
| 绑定 | 仅 `127.0.0.1`（绝不监听 0.0.0.0）|
| 协议版本 | 2024-11-05 / 2025-03-26 / 2025-06-18 三值协商 |

令牌在 **终端页 → 顶栏 Hub 图标（MCP 控制中心）** 查看/复制/再生成。
端口与开关同页管理（改端口即时重启监听；再生成令牌无需重启）。

### 2.2 RikkaHub（同机直连）

MCP 设置 → 添加服务器 → **Streamable HTTP**：

- URL：`http://127.0.0.1:8765/mcp`
- Headers：`Authorization: Bearer <token>`

### 2.3 Claude Code（桌面，经 USB）

```bash
# 1. USB 端口转发（桌面 localhost:8765 ↔ 设备 :8765）
adb reverse tcp:8765 tcp:8765

# 2. 注册 MCP 服务器
claude mcp add --transport http theocracy \
  http://127.0.0.1:8765/mcp \
  --header "Authorization: Bearer <token>"
```

### 2.4 Cursor（桌面，经 USB）

`~/.cursor/mcp.json`：

```json
{
  "mcpServers": {
    "theocracy": {
      "url": "http://127.0.0.1:8765/mcp",
      "headers": { "Authorization": "Bearer <token>" }
    }
  }
}
```

## 3. 工具面（12 个）

### 3.1 执行与读屏

| 工具 | 入参 | 语义 |
|---|---|---|
| `run` | `command`, `session_id?`, `timeout_ms?=60000`, `max_output_bytes?=20000` | 阻塞执行：run → 事件等待退出 → 收割输出。**退出码来自 OSC 633 shell marker（waitpid 确证）**；超时不杀进程（`timed_out=true` + partial）；输出经 ANSI 剥离 + head/tail 折叠（150+50 行）|
| `read_screen` | `session_id?`, `include_scrollback?=false`, `scrollback_lines?=50` | **语义化读屏**：`live_screen` 行数组 + `cursor{row,col}` + `alternate_screen` + `cwd`（OSC 7）+ `last_exit_code` + `has_foreground_process` + stable scrollback 尾部 |
| `write` | `text`, `session_id?`, `kind?=line` | 交互输入：run 超时后读屏看到 `Password:` / `[Y/n]` → 写入（line/raw）|

### 3.2 会话与信号

| 工具 | 语义 |
|---|---|
| `list_sessions` | 会话簿：state / alive / shell / cwd / rows×cols / last_exit_code / 前后台 job |
| `send_signal` | 前台作业组信号（Ctrl-C 语义：打断当前命令、shell 存活）；idle shell 返回 `delivered=false` |
| `resize` | PTY 几何同步（TIOCSWINSZ + VT resize）|

### 3.3 安全与审计

| 工具 | 语义 |
|---|---|
| `gate_status` | 门控状态：待确认单列表 / 单条查询 / 批准票计数 |
| `events` | JSONL 审计流：`command_proposed → gate_decision → command_executed → tool_call` 全链重放；event_type 过滤 + since_seq 游标翻页 + 每工具 p50/p95 延迟与错误率 |

### 3.4 托管进程（长任务非阻塞）

| 工具 | 语义 |
|---|---|
| `process_spawn` | 长任务（npm run dev / 编译 / 日志 tail）派生独立会话，立即返回 handle |
| `process_read` | `from_cursor` 游标**非阻塞增量 poll**；退出进程保留可读（最终输出 + exit_code）|
| `process_list` | 注册表 × runtime 状态对账（alive / state / exit_code）|
| `process_kill` | 三级收尸（SIGTERM → 宽限 → SIGKILL）；shell 会话存活 |

### 3.5 共享会话语义

`session_id` 缺省时复用（或按需创建）**默认共享会话**（40×120 宽幅）：cd /
环境变量 / shell 别名跨命令连续 —— 短命令走共享会话，长任务走 process 通道。

## 4. 安全模型

### 4.1 传输层

- 仅绑定 127.0.0.1；Bearer 令牌（256 位首启自动生成，**常量时间比较**）
- Origin 白名单（localhost/127.0.0.1/[::1]，防 DNS rebinding）；鉴权先于方法路由

### 4.2 写入前门控（四级，本地确定性裁定，不依赖模型）

命令一旦进入 PTY 就无法拦截 —— **门控必须在写入前**。分类器（纯词法 + 结构
分析）四级判定：

| 级别 | 例 | 策略 |
|---|---|---|
| READ_ONLY | ls / cat / grep / dumpsys / git status | 静默放行 |
| MUTATING | mkdir / git commit / am start /（默认桶）| 自动放行 + 高亮 |
| DANGEROUS | rm -r / chmod 777 / dd / pm uninstall / input 注入 / 写系统目录 | **人工确认**：`confirmation_required + confirm_id`（命令不执行）→ 人侧在 MCP 控制中心准许 → **重试同命令放行（一次性批准票，120s TTL）** |
| CRITICAL | 写块设备 / su 提权 / mkfs / 批量删根级路径 / 设备重启 | **拒绝执行，不可确认绕过** |

绕过防护（kelai141/dsh PR#756 教训）：`--` 选项终结符跳过（`trap -- 'rm -rf
/sdcard' EXIT` 的载荷不被遮蔽）；包装器载荷递归（sh -c / eval / trap / xargs /
busybox / find -exec / 前缀剥离族）；嵌套超 4 层保守归 DANGEROUS；载荷不可见
（`sh <file>`）保守归 DANGEROUS。

**纵深防御**：MCP 门控之外，`TerminalPolicyImpl` 在 writePty 前对 CRITICAL
硬拒（`RISK_CRITICAL` 拒绝理由）—— 门控被绕过时平台层仍兜底。

### 4.3 审计

每次 run 的决策链在 JSONL 事件流中逐行可重放（`filesDir/logs/terminal-events.jsonl`，
2MB 双文件轮转，seq 跨轮转与重启单调连续）：

```json
{"seq":1,"ts":1760000000123,"event":"command_proposed","session":100,"tool":"run","cmd":"ls"}
{"seq":2,"ts":1760000000124,"event":"gate_decision","session":100,"decision":"allow","risk":"READ_ONLY","reason":"'ls' is an observation tool"}
{"seq":3,"ts":1760000000031,"event":"command_executed","session":100,"cmd":"ls","exit_code":0,"timed_out":false,"truncated":false,"duration_ms":6}
{"seq":4,"ts":1760000000032,"event":"tool_call","tool":"run","duration_ms":9,"error":false}
```

## 5. 设计决策（ADR 摘要）

**为什么不官方 MCP Kotlin SDK？** kotlin-sdk 0.16.0 依赖 Ktor 全家桶（不传递
engine，需自配），且 RikkaHub 维护了自己的 fork —— Android 内嵌场景兼容坑多。
NanoHTTPD 2.3.1 虽 2016 终版但功能冻结、久经考验、thread-per-connection 与
「纯 JSON 响应」最小合规路线（spec 2025-06-18：服务器可对 POST 回
application/json，客户端必须支持）精确匹配。STRX 已验证该路线在真实客户端下可行。

**为什么 OSC 633 + OSC 7 双标记？** 退出码与 cwd 是 Agent 终端的两大语义缺口。
OSC 633（VS Code shell-integration 家族）给出**真实退出码**（printf 包裹在命令
尾部同一 shell 行，`$?` 即时求值；waitpid 确证，绝无输出静默推断）；OSC 7（VTE/
kitty/ghostty 通用 cwd 协议）给出**实时工作目录**。双帧同 printf 发射：Agent
命令无 rc 依赖（MCP 共享会话不带 ENV）；人类会话另有 rc 的 cd() 函数通道。
设备端 native VT 引擎不解析 OSC 7 —— 平台层字节流侧车（与 633 同挂点）补齐，
引擎选择无关。

**为什么门控在 MCP 层 + 平台层双边界？** MCP 门控（SecurityGate）承担确认流
（一次性批准票 + 人侧 UI）；平台层 TerminalPolicyImpl 的 CRITICAL 硬拒是独立
兜底 —— 单一边界被绕过时另一道仍拦住 `rm -rf /`。

**为什么 run 超时不杀进程？** 超时 ≠ 失败：Agent 可 send_signal 打断、可
read_screen 看等待什么、可后续 process 工具收割 —— 把「杀不杀」的决定权留给
调用方，与 nl2sh 的 Execution 语义对齐。

## 6. 与同类方案对比

| 能力 | Theocracy | termux-mcp | STRX | Operit |
|---|---|---|---|---|
| 真实 PTY（Agent 拿到 TTY 标记）| ✅ forkpty JNI | Termux 环境 | ❌ 直 exec | ✅ |
| 真实退出码 | ✅ OSC 633 + waitpid | ✅ | ❌ | 部分 |
| 语义化读屏（live/scrollback/cursor/alt/cwd）| ✅ | ❌ | ❌ | ❌ |
| 四级风险门控 + 人工确认 | ✅ | ✅（四层 gate）| ⚠️ 黑名单 | ❌ |
| 长任务非阻塞 poll | ✅ process 工具 | ❌ | ❌ | ✅（Flow）|
| JSONL 审计 + p50/p95 | ✅ | ✅ | ❌ | ❌ |
| 人类同屏观察（人机共视）| ✅ VT 全屏视图 | Termux 本体 | Termux fork | 自带 |
| 桌面/同机客户端 | ✅ Claude Code / Cursor / RikkaHub | 桌面 | 桌面 | — |

## 7. 快速验证（curl）

```bash
TOKEN="<控制中心复制的令牌>"
BASE="http://127.0.0.1:8765/mcp"

# 握手
curl -s $BASE -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'

# 工具面
curl -s $BASE -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 执行（真实 TTY + 真实退出码）
curl -s $BASE -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"run","arguments":{"command":"pwd && echo $TERM"}}}'
```

预期 `run` 返回含 `exit_code: 0` 与 `/sdcard` / `xterm-256color`（真实 TTY 标记）。

## 8. 运维

- 事件流：`events` 工具（`since_seq` 翻页）；文件在 `filesDir/logs/`
- 度量：`events` 的 `metrics` 字段（每工具 p50/p95/calls/errors，最近 100 次窗口）
- 待确认：MCP 控制中心「待确认」队列（准许一次 / 拒绝）；`gate_status` 工具轮询
- 门控失效语义：服务器重启后未决单与批准票清零（内存态）—— Agent 重试将得到
  新的 confirmation_required
