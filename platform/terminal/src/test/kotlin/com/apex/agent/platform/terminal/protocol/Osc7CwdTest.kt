package com.apex.agent.platform.terminal.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-OSC7 — Osc7CwdParser 单测：帧提取 / chunk 边界 / host 与路径分拆 /
 * 百分号解码 / 双终结符（BEL 与 ST）。
 */
class Osc7CwdTest {

    private fun bel(path: String, host: String = "") =
        "\u001b]7;file://$host$path\u0007"

    private fun st(path: String, host: String = "") =
        "\u001b]7;file://$host$path\u001b\\"

    @Test
    fun `parses bel-terminated frame with host`() {
        val updates = Osc7CwdParser().feed(bel("/sdcard/下载", host = "localhost").toByteArray(), 0)
        assertEquals(1, updates.size)
        assertEquals("/sdcard/下载", updates[0].path)
    }

    @Test
    fun `parses st-terminated frame with empty host`() {
        val updates = Osc7CwdParser().feed(st("/data/local/tmp").toByteArray(), 0)
        assertEquals("/data/local/tmp", updates[0].path)
    }

    @Test
    fun `frame spanning chunk boundary is completed by later chunk`() {
        val parser = Osc7CwdParser()
        val frame = bel("/sdcard")
        val split = frame.length / 2
        val first = parser.feed(frame.substring(0, split).toByteArray(), 0)
        val second = parser.feed(frame.substring(split).toByteArray(), split.toLong())
        assertTrue(first.isEmpty())
        assertEquals(1, second.size)
        assertEquals("/sdcard", second[0].path)
    }

    @Test
    fun `multiple frames in one chunk all returned in stream order`() {
        val parser = Osc7CwdParser()
        val bytes = (bel("/a") + "some output\n" + bel("/b")).toByteArray()
        val updates = parser.feed(bytes, 0)
        assertEquals(listOf("/a", "/b"), updates.map { it.path })
    }

    @Test
    fun `bare host without path is ignored`() {
        val updates = Osc7CwdParser().feed("\u001b]7;file://localhost\u0007".toByteArray(), 0)
        assertTrue(updates.isEmpty())
    }

    @Test
    fun `percent-encoded path is decoded`() {
        val updates = Osc7CwdParser().feed(bel("/sdcard/my%20docs/a%2Fb").toByteArray(), 0)
        assertEquals("/sdcard/my docs/a/b", updates[0].path)
    }

    @Test
    fun `decodePath keeps malformed sequences verbatim`() {
        assertEquals("a%zz", Osc7Cwd.decodePath("a%zz"))
        assertEquals("tail%", Osc7Cwd.decodePath("tail%"))
        assertEquals("plain", Osc7Cwd.decodePath("plain"))
    }

    @Test
    fun `decodePath decodes hex pairs`() {
        assertEquals("AB", Osc7Cwd.decodePath("%41%42"))
        assertEquals("a b", Osc7Cwd.decodePath("a%20b"))
    }

    @Test
    fun `osc7 frame inside mixed vt output is extracted`() {
        // 真实流：命令输出 + OSC 7 + 提示符重绘混在一个 chunk
        val prefix = "done\n"
        val frame = bel("/data/user/0")
        val chunk = prefix + frame + "\$ "
        val updates = Osc7CwdParser().feed(chunk.toByteArray(), 100)
        assertEquals("/data/user/0", updates.single().path)
        assertEquals(100L + prefix.length + frame.length, updates.single().atCursor)
    }

    @Test
    fun `non-osc7 osc frames do not match`() {
        val updates = Osc7CwdParser().feed(
            ("\u001b]0;window title\u0007" + "\u001b]633;APEX;j=1;e=0\u0007").toByteArray(),
            0,
        )
        assertTrue(updates.isEmpty())
    }
}
