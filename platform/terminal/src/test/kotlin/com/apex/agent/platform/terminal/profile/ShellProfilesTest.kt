package com.apex.agent.platform.terminal.profile

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T87：GuestShellProfile（Android mksh rc）内容不变式。
 */
class GuestShellProfileTest {

    @Test
    fun `prompt has user host cwd context`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("PS1="))
        assertTrue(rc.contains("${'$'}{USER}@${'$'}{HOSTNAME}"))
    }

    @Test
    fun `no bash-only ps1 escapes (mksh width safety)`() {
        val rc = GuestShellProfile.generate()
        // \[ \] 是 bash readline 专属 —— mksh 不识别会让行编辑光标错位
        assertFalse(rc.contains("\\["))
        assertFalse(rc.contains("\\]"))
        // 提示符不携带 ANSI 转义
        assertFalse(rc.contains("PS1=.*\\e".toRegex()))
    }

    @Test
    fun `command discovery helpers exist`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("cmds()"))
        assertTrue(rc.contains("cmdf()"))
        assertTrue(rc.contains("guru_help"))
    }

    @Test
    fun `aliases only use toybox-safe forms`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("alias ll='ls -alF'"))
        // 不给本地 shell 塞 GNU 专属开关
        assertFalse(rc.contains("ls --color"))
    }

    @Test
    fun `history is guarded by writable home probe`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("HISTFILE"))
        assertTrue(rc.contains("touch \"${'$'}HOME/.h\""))
    }

    @Test
    fun `rc ends with true (source never fails)`() {
        val rc = GuestShellProfile.generate()
        assertTrue(rc.trimEnd().endsWith("true"))
    }

    @Test
    fun `shellEnv carries home env term colorterm`() {
        val env = GuestShellProfile.shellEnv("/data/home", "/data/home/.gurc")
        assertEquals("/data/home", env["HOME"]!!)
        assertEquals("/data/home/.gurc", env["ENV"]!!)
        assertEquals("xterm-256color", env["TERM"]!!)
        assertEquals("truecolor", env["COLORTERM"]!!)
    }

    @Test
    fun `host tool guards redirect to on-device equivalents`() {
        // T95：真机日志里用户连跑 adb shell getprop / systemd-analyze /
        // lsb_release —— mksh 无 command_not_found 钩子，只能裸报 not found。
        // rc 必须为这类宿主工具给出口径 + 设备侧正确替代。
        val rc = GuestShellProfile.generate()
        assertTrue(rc.contains("adb()"))
        assertTrue(rc.contains("systemd-analyze()"))
        assertTrue(rc.contains("systemctl()"))
        assertTrue(rc.contains("lsb_release()"))
        // 引导必须给出设备侧正确替代（getprop 家族），而不是只说「不可用」
        assertTrue(rc.contains("getprop ro.build.version.release"))
        assertTrue(rc.contains("getprop <name>"))
    }

    private fun assertEquals(expected: String, actual: String) =
        org.junit.Assert.assertEquals(expected, actual)
}
