package com.apex.agent.ui.screen.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.apex.agent.platform.terminal.io.TerminalKey
import com.apex.agent.terminalview.TerminalContextMenuItem
import com.apex.agent.terminalview.TerminalKey as VtKey
import com.apex.agent.terminalview.TerminalMouseEventType
import com.apex.agent.terminalview.TerminalViewClient
import com.apex.agent.terminalemulator.MouseTrackingMode

/**
 * T-VIEW — TerminalView ↔ TerminalRuntime 桥（terminal 十连 PR 8/10）。
 *
 * terminal-view 模块是「纯显示组件」（T88：Canvas 网格 / 滚动 / 选区 / IME），
 * PTY、I/O、连接全部由调用方管理 —— 本桥把它的回调接进 TerminalViewModel 的
 * runtime 写链，把 styledScreenFlow 的快照喂回 submit（[TerminalScreen.VtPane]）。
 *
 * 人机共视：人类在 VT 视图看到的与 Agent 经 read_screen 读到的是**同一份
 * VT 态**（styledScreenFlow 与 observe(SCREEN) 同源 ObservationEngine）。
 *
 * v1 范围：文本/按键/控制字符写入、resize 握手、剪贴板复制/粘贴、链接打开。
 * 鼠标上报、DECSET 1004 焦点报告、铃振、上下文菜单浮层为 no-op（后续按需接）。
 */
class VtTerminalBridge(
    private val context: Context,
    private val viewModel: TerminalViewModel,
    private val sessionId: Long,
) : TerminalViewClient {

    override fun onTerminalWrite(text: String) {
        viewModel.vtWriteText(sessionId, text)
    }

    override fun onTerminalKey(key: VtKey, mods: Int) {
        mapKey(key)?.let { viewModel.vtWriteKey(sessionId, it) }
    }

    override fun onTerminalControlChar(code: Int) {
        viewModel.vtWriteControlChar(sessionId, code)
    }

    override fun onTerminalMouse(
        col: Int,
        row: Int,
        type: TerminalMouseEventType,
        mods: Int,
        mouseMode: MouseTrackingMode,
        released: Boolean,
        button: Int,
    ) {
        // v1：无鼠标上报需求（mouseMode.enabled 时 VT 层才会派发到这里）
    }

    override fun onTerminalFocus(gained: Boolean) {
        // v1：DECSET 1004 焦点报告不接
    }

    override fun onTerminalViewSizeChanged(rows: Int, cols: Int, pixelWidth: Int, pixelHeight: Int) {
        viewModel.vtResize(sessionId, rows, cols)
    }

    override fun onTerminalBell() {
        // v1：铃振静默（振动/提示音是产品决策，后续接设置项）
    }

    override fun onTerminalTitle(title: String) {
        // 会话标题由会话簿管理（tab 显示），VT 标题不回写
    }

    override fun onTerminalClipboardCopy(text: String) {
        clipboard()?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onTerminalPasteRequest() {
        val clip = clipboard()?.primaryClip ?: return
        if (clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(context)?.toString()?.let(::onTerminalWrite)
        }
    }

    override fun onTerminalLinkOpen(uri: String) {
        // 外链出口 scheme 白名单交由全局外链守卫；此处仅透传 ACTION_VIEW
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        }
    }

    override fun onTerminalScrollChanged(topRow: Int, atBottom: Boolean) {
        // View 内部自带「跳到最新」指示，宿主无需 affordance
    }

    override fun onTerminalSelectionChanged(selectedText: String?) {
        // 选区状态留在 View 内（复制浮标由 View 呈现）
    }

    override fun onTerminalFontSizeChanged(newSp: Float) {
        // 字号变化留在 View 会话内（设置中心的全局字号仍是权威源）
    }

    override fun onTerminalContextMenu(items: List<TerminalContextMenuItem>, x: Float, y: Float) {
        // v1：上下文菜单浮层不接（View 自带复制/粘贴浮标路径可用）
    }

    private fun clipboard(): ClipboardManager? =
        ContextCompat.getSystemService(context, ClipboardManager::class.java)

    /** VT 键枚举（terminal-emulator）→ Runtime 写链键枚举（platform io）。 */
    private fun mapKey(key: VtKey): TerminalKey? = when (key) {
        VtKey.UP -> TerminalKey.ARROW_UP
        VtKey.DOWN -> TerminalKey.ARROW_DOWN
        VtKey.LEFT -> TerminalKey.ARROW_LEFT
        VtKey.RIGHT -> TerminalKey.ARROW_RIGHT
        VtKey.HOME -> TerminalKey.HOME
        VtKey.END -> TerminalKey.END
        VtKey.PAGE_UP -> TerminalKey.PAGE_UP
        VtKey.PAGE_DOWN -> TerminalKey.PAGE_DOWN
        VtKey.INSERT -> TerminalKey.INSERT
        VtKey.DELETE -> TerminalKey.DELETE
        VtKey.ENTER -> TerminalKey.ENTER
        VtKey.NUMPAD_ENTER -> TerminalKey.ENTER
        VtKey.TAB -> TerminalKey.TAB
        VtKey.BACKSPACE -> TerminalKey.BACKSPACE
        VtKey.ESCAPE -> TerminalKey.ESC
        VtKey.F1 -> TerminalKey.F1
        VtKey.F2 -> TerminalKey.F2
        VtKey.F3 -> TerminalKey.F3
        VtKey.F4 -> TerminalKey.F4
        VtKey.F5 -> TerminalKey.F5
        VtKey.F6 -> TerminalKey.F6
        VtKey.F7 -> TerminalKey.F7
        VtKey.F8 -> TerminalKey.F8
        VtKey.F9 -> TerminalKey.F9
        VtKey.F10 -> TerminalKey.F10
        VtKey.F11 -> TerminalKey.F11
        VtKey.F12 -> TerminalKey.F12
        else -> null // SPACE/小键盘数字：无 runtime 键语义，忽略（文本路径可覆盖）
    }
}
