<!-- pr: terminal-mcp-read-screen -->

- read_screen 语义化读屏工具：live_screen 行数组、光标行列、alternate_screen、cursor_visible、title、cwd、最近退出码、前台进程标记 —— Agent 读屏不再拿原始 ANSI 字节
- OSC 7 cwd 双通道桥（T-OSC7）：rc 的 cd() 函数 + 启动帧（人类会话/带 ENV 会话）；命令包装行 printf 追加 OSC 7 帧（MCP 共享会话，无 rc 依赖）—— 设备端 native 引擎不解析 OSC 7 的缺口由平台层侧车补齐
- Osc7CwdParser：chunk 边界安全的字节流侧车解析器（与 ShellMarkerParser 同款 carry 模式；host/path 分拆 + 防御性百分号解码），挂在 TerminalRuntimeImpl 的输出事件收集协程（与 633 同挂点、独立生命周期）
- SemanticStateReducer.updateCwd：会话簿 cwd 实时更新（UI 会话 tab 的 cwd 展示与 read_screen 同源）；ObservationEngine SCREEN 投影在引擎 guestCwd 为空时回填 reducer 侧车值
- write 交互输入工具：run 超时 → read_screen 看到 Password:/[Y/n] → 写入（line/raw 两形态）—— 交互命令闭环
- 单测：Osc7CwdParser 10 项（chunk 边界/双终结符/host 分拆/解码）+ E2E 集成 2 项（Agent 包装路径 + rc 注入路径，双断言 reducer 侧车与 SCREEN 投影）+ read_screen/write 映射 7 项
