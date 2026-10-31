<!-- pr: terminal-view-wiring -->

- terminal-view 模块（T88 Termux 级自绘 View：Canvas 网格/滚动/选区/IME）接回终端页：顶栏新增视图切换（VT 交互视图 ↔ 控制台行视图），VT 模式下隐藏行式输入栏（TerminalView 自带 IME）
- VtTerminalBridge：TerminalViewClient 桥接 —— 文本/按键（VT 键枚举 → runtime 键枚举映射）/控制字符写入 runtime（owner=USER）、resize 握手（视口→网格→PTY TIOCSWINSZ）、剪贴板复制/粘贴、外链 ACTION_VIEW
- 人机共视：styledScreenFlow（33ms 节流）→ TerminalView.submit；与 Agent 的 read_screen 同源（同一 ObservationEngine 的 VT 态）—— Phase 3 验收标准（人类看到的 = Agent 读到的）
- TerminalViewModel 新增 VT 写链四方法（快照流/文本/键/控制字符/resize）
- 双语字符串 2 键（term_view_mode_vt/console，i18n 镜像门禁通过）
