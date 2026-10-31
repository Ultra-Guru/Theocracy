package com.apex.agent.platform.terminal.protocol

/**
 * T-OSC7 — OSC 7 cwd 上报侧车（terminal 十连 PR 3/10：read_screen 语义化）。
 *
 * ## 为什么需要侧车
 * [com.apex.agent.platform.terminal.screen.TerminalScreenState.guestCwd] 的引擎
 * 覆盖是不对等的：JVM 回退引擎（TerminalCore，CI/单测）已解析 OSC 7，而设备端
 * native 引擎（NativeVtCore）的 C++ 侧不解析 —— 设备上 guestCwd 恒 null。本
 * 侧车在平台层（TerminalRuntimeImpl 的输出事件收集协程，与 ShellMarkerParser
 * 同一挂点）直接扫描原始字节流，**与引擎选择无关**地恢复 cwd 语义；JVM 引擎
 * 已填值时保持引擎值（两侧在 CI 下同值，见 ObservationEngine 的 SCREEN 合并）。
 *
 * ## OSC 7 从哪来（双通道）
 * 1. [ShellMarkers.wrapCommand] —— Agent 命令包装行在 OSC 633 帧后追加
 *    OSC 7（$PWD）：无 rc 依赖，MCP 共享会话（不带 ENV）也有 cwd；
 * 2. GuestShellProfile 的 rc —— `cd()` 函数 + 启动时各发一帧：覆盖人类会话
 *    与带 ENV 的会话（rc 路径的初始 cwd 也上报）。
 *
 * ## 帧格式
 * `ESC ] 7 ; file://<host><path> (BEL | ESC \)`（VTE/kitty/ghostty 的通用
 * cwd 协议；VS Code 同源）。host 允许为空（`file:///sdcard`）；path 为绝对
 * 路径。路径编码两种形态都支持：mksh 的 `$PWD` 原样 UTF-8 字节；规范形态的
 * 百分号编码 UTF-8（%XX 序列先解到字节再按 UTF-8 解码，与 VS Code 对齐）。
 */
object Osc7Cwd {
    const val OSC_CODE = 7

    /**
     * 路径解码（字节级）：ISO_8859_1 视图回到原始字节 → 含 % 时先百分号解到
     * 字节 → 按 UTF-8 解码。非法序列（孤立的 % / 非法 UTF-8）尽量原样保留
     *（替换字符兑底），绝不抛异常。
     */
    fun decodePath(raw: String): String {
        val rawBytes = raw.toByteArray(Charsets.ISO_8859_1)
        val decoded = if (rawBytes.indexOf(PERCENT) >= 0) percentDecode(rawBytes) else rawBytes
        return decoded.toString(Charsets.UTF_8)
    }

    private val PERCENT = '%'.code.toByte()

    /** 百分号解码到字节（非法 %XX 原样保留该字节）。 */
    private fun percentDecode(bytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == PERCENT && i + 3 <= bytes.size) {
                val hex = String(bytes, i + 1, 2, Charsets.US_ASCII)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    out.write(code)
                    i += 3
                    continue
                }
            }
            out.write(b.toInt())
            i++
        }
        return out.toByteArray()
    }
}

/** 一次 OSC 7 上报。`atCursor` = ring 游标（帧结束字节的下一个位置，与 ShellMarker 对齐）。 */
data class Osc7CwdUpdate(
    val path: String,
    val atCursor: Long,
)

/**
 * Chunk 边界安全的 OSC 7 解析器 —— 与 [ShellMarkerParser] 同款 carry 模式：
 * 保留跨 chunk 的尾部字节，正则扫描完成后收缩 carry。OSC 7 帧可比 633 长
 * 得多（长路径），carry 上限相应放大。
 */
class Osc7CwdParser {

    // ESC ] 7 ; file:// host/path (BEL | ESC \) —— 载荷内不得出现 BEL/ESC
    private val pattern = Regex("\u001b\\]7;file://[^\u0007\u001b]*(?:\u0007|\u001b\\\\)")

    private var carry = StringBuilder()

    /**
     * @param atCursorBase 该 chunk 首字节的 ring 游标。
     * @return 本 chunk 完成的上报（流序）。同 chunk 多帧全部返回（cd 频繁时
     *   每帧都算 —— reducer 端去重，最新值即真相）。
     */
    fun feed(bytes: ByteArray, atCursorBase: Long): List<Osc7CwdUpdate> {
        if (bytes.isEmpty()) return emptyList()
        carry.append(String(bytes, Charsets.ISO_8859_1))
        val out = mutableListOf<Osc7CwdUpdate>()
        var consumedUpTo = 0
        for (m in pattern.findAll(carry)) {
            extractPath(m.value)?.let { path ->
                out.add(Osc7CwdUpdate(path = path, atCursor = atCursorBase + m.range.last + 1))
                consumedUpTo = m.range.last + 1
            }
        }
        if (consumedUpTo > 0 || carry.length > MAX_CARRY) {
            carry.delete(0, if (consumedUpTo > 0) consumedUpTo else carry.length - MAX_CARRY)
        }
        return out
    }

    /** 从完整帧提取路径：剥引入序列与终结符 → 剥 host（首个 / 之前）→ 百分号解码。 */
    private fun extractPath(frame: String): String? {
        // frame = ESC ] 7 ; file://<payload>(BEL|ESC \)
        var payload = frame.substring(FILE_PREFIX_END)
        payload = when {
            payload.endsWith("\u0007") -> payload.dropLast(1)
            payload.endsWith("\u001b\\") -> payload.dropLast(2)
            else -> return null
        }
        if (payload.isEmpty()) return null
        val pathStart = payload.indexOf('/')
        if (pathStart < 0) return null // 裸 host（file://localhost 无路径）—— 无 cwd 语义
        // pathStart == 0 是合法的空主机形式（file:///sdcard → path=/sdcard）
        return Osc7Cwd.decodePath(payload.substring(pathStart))
    }

    private companion object {
        /** "ESC]7;file://" 的字符长度（帧内前缀剥离起点）。 */
        const val FILE_PREFIX_END = "\u001b]7;file://".length

        /** carry 上限：OSC 7 帧含路径，按 4KB 路径 + 帧结构放余量。 */
        const val MAX_CARRY = 8192
    }
}
