package com.apex.agent.platform.terminal.policy

import com.apex.agent.platform.terminal.io.InputOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-RISK — 四级风险分类器共享 fixture 矩阵（terminal 十连 PR 4/10）。
 *
 * 用例表刻意做成**共享 fixture 形态**（命令 → 期望级别对）：PR 5/10 的
 * SecurityGate 壳层与本分类器同构消费同一张表 —— 门禁引擎与执行壳层
 * 共享 fixture 是 kelai141/dsh PR#756 的核心教训（引擎与壳层各自为政时，
 * `--` 绕过只在其中一侧被修）。
 */
class RiskClassifierTest {

    private val classifier = RiskClassifier()

    // ─── 共享 fixture：命令 → 期望级别 ───

    private val fixtures: List<Pair<String, RiskLevel>> = listOf(
        // READ_ONLY：观察类 + 无写入结构
        "ls" to READ_ONLY,
        "ls -la /sdcard" to READ_ONLY,
        "cat /proc/cpuinfo" to READ_ONLY,
        "cat notes.txt | grep -i todo" to READ_ONLY,
        "pwd" to READ_ONLY,
        "echo hello world" to READ_ONLY,
        "git status" to READ_ONLY,
        "git log --oneline -5" to READ_ONLY,
        "git diff HEAD~1" to READ_ONLY,
        "getprop ro.build.version.release" to READ_ONLY,
        "dumpsys window policy" to READ_ONLY,
        "pm list packages" to READ_ONLY,
        "settings get system screen_brightness" to READ_ONLY,
        "find /sdcard -name '*.jpg'" to READ_ONLY,
        "df -h /data" to READ_ONLY,
        "ps -A | grep system_server" to READ_ONLY,
        "sed 's/a/b/' file.txt" to READ_ONLY,
        "logcat -d -t 100" to READ_ONLY,
        "cd /sdcard && ls" to READ_ONLY,
        "" to READ_ONLY,
        "   " to READ_ONLY,

        // MUTATING：默认桶（状态改变但破坏面有限）
        "mkdir newdir" to MUTATING,
        "touch file.txt" to MUTATING,
        "cp a.txt b.txt" to MUTATING,
        "mv a.txt b.txt" to MUTATING,
        "rm single.txt" to MUTATING,
        "git commit -m 'wip'" to MUTATING,
        "git add -A" to MUTATING,
        "echo done > out.txt" to MUTATING,
        "am start -n com.example/.MainActivity" to MUTATING,
        "curl -s https://example.com" to MUTATING,
        "wget https://example.com/f" to MUTATING,
        "sed -i 's/a/b/' file.txt" to MUTATING,
        "logcat -c" to MUTATING,
        "settings put system screen_brightness 128" to MUTATING,
        "some-unknown-tool --flag" to MUTATING,
        "unknown_binary" to MUTATING,
        "pip install requests" to MUTATING,
        "npm install left-pad" to MUTATING,

        // DANGEROUS：破坏面显著（人工确认级）
        "rm -rf build/" to DANGEROUS,
        "rm -fr node_modules" to DANGEROUS,
        "rm -r /sdcard/DCIM/Camera" to DANGEROUS,
        "chmod 777 script.sh" to DANGEROUS,
        "chmod -R 755 /sdcard/project" to DANGEROUS,
        "chown -R root:root /data/local/tmp/x" to DANGEROUS,
        "dd if=/dev/zero of=/sdcard/blank.img bs=1M count=10" to DANGEROUS,
        "pm uninstall com.some.app" to DANGEROUS,
        "pm clear com.some.app" to DANGEROUS,
        "pm disable com.some.app" to DANGEROUS,
        "settings put secure location_mode 3" to DANGEROUS,
        "settings put global airplane_mode_on 1" to DANGEROUS,
        "am force-stop com.some.app" to DANGEROUS,
        "kill 12345" to DANGEROUS,
        "killall logd" to DANGEROUS,
        "input tap 540 960" to DANGEROUS,
        "input text 'hello'" to DANGEROUS,
        "input keyevent 4" to DANGEROUS,
        "mount -o remount,rw /system" to DANGEROUS,
        "umount /sdcard" to DANGEROUS,
        "git reset --hard origin/main" to DANGEROUS,
        "git clean -fd" to DANGEROUS,
        "find /sdcard -name '*.tmp' -delete" to DANGEROUS,
        "find / -name core -exec rm {} +" to DANGEROUS,
        "echo x > /system/build.prop" to DANGEROUS,
        "cat f >> /data/local/tmp/notes" to MUTATING, // 追加普通路径：写但非系统目录
        "sh -c 'echo hi'" to READ_ONLY, // shell 载荷全只读
        "sh -c 'cd /tmp && ls'" to READ_ONLY,
        "sh -c 'rm -rf /sdcard/tmpdir'" to DANGEROUS, // 载荷递归命中
        "env rm single.txt" to MUTATING, // 前缀剥离后 rm 非递归
        "xargs rm" to DANGEROUS, // 破坏面工具 + 运行时目标
        "xargs grep foo" to MUTATING,
        "busybox rm -rf /data/local/tmp/x" to DANGEROUS,
        "watch rm -rf build" to DANGEROUS,
        "eval 'pm clear com.some.app'" to DANGEROUS,
        "sh < /sdcard/script.sh" to DANGEROUS, // 载荷不可见：动态执行保守归高危
        "sh" to DANGEROUS, // 裸子 shell
        "truncate -s 0 /data/local/tmp/log" to MUTATING,

        // CRITICAL：不可逆灾难级（拒绝执行）
        "rm -rf /" to CRITICAL,
        "rm -rf /*" to CRITICAL,
        "rm -fr /sdcard" to CRITICAL,
        "rm -r /data" to CRITICAL,
        "rm -rf /storage/*" to CRITICAL,
        "chmod -R 777 /" to CRITICAL,
        "chmod 777 /system" to CRITICAL,
        "chmod -R 777 /sdcard" to CRITICAL,
        "dd if=/dev/zero of=/dev/block/sda" to CRITICAL,
        "dd if=/sdcard/img of=/dev/block/by-name/system" to CRITICAL,
        "echo x > /dev/block/sda" to CRITICAL,
        "su" to CRITICAL,
        "sudo ls" to CRITICAL,
        "su -c 'ls'" to CRITICAL,
        "magisk install" to CRITICAL,
        "mkfs.ext4 /dev/block/sda" to CRITICAL,
        "mkfs.vfat /dev/sda1" to CRITICAL,
        "reboot" to CRITICAL,
        "shutdown -h now" to CRITICAL,
        "halt" to CRITICAL,
        "poweroff" to CRITICAL,
        "flash_image boot /sdcard/boot.img" to CRITICAL,
        "wipefs -a /dev/sda" to CRITICAL,
        "fdisk /dev/sda" to CRITICAL,
        "parted /dev/sda rm 1" to CRITICAL,
        "echo \$(rm -rf /)" to CRITICAL, // 命令替换自成一段
        "echo `reboot`" to CRITICAL,
        "sh -c 'rm -rf /data'" to CRITICAL, // 载荷递归命中 CRITICAL
        "env rm -rf /sdcard" to CRITICAL,
        "nohup dd if=/dev/zero of=/dev/block/sda" to CRITICAL,
        "trap -- 'rm -rf /sdcard' EXIT" to CRITICAL, // PR#756：-- 不遮蔽载荷
        "trap 'reboot' EXIT" to CRITICAL,
    )

    @Test
    fun `shared fixture matrix`() {
        val failures = mutableListOf<String>()
        for ((index, fixture) in fixtures.withIndex()) {
            val (command, expected) = fixture
            val actual = classifier.classify(command).level
            if (actual != expected) {
                failures.add("[$index] \"$command\": expected $expected but was $actual")
            }
        }
        assertTrue(
            "fixture mismatches:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    // ─── heredoc / 多行注入 ───

    @Test
    fun `heredoc body lines are classified line by line`() {
        val assessment = classifier.classify("cat <<'EOF'\nrm -rf /\nEOF")
        assertEquals(CRITICAL, assessment.level)
    }

    @Test
    fun `multi-line commands take the worst line`() {
        val assessment = classifier.classify("ls\necho hi\npm uninstall com.x")
        assertEquals(DANGEROUS, assessment.level)
    }

    // ─── 深度防护 ───

    @Test
    fun `wrapper nesting beyond limit degrades to dangerous`() {
        var nested = "rm -rf /"
        repeat(6) { nested = "sh -c '$nested'" }
        val assessment = classifier.classify(nested)
        assertTrue(assessment.level >= DANGEROUS)
    }

    // ─── TerminalPolicyImpl 纵深防御接入 ───

    @Test
    fun `policy impl hard-denies critical even when denylist allows`() {
        val impl = TerminalPolicyImpl(commandPolicy = CommandPolicy(denylist = emptySet()))
        val decision = impl.check(
            com.apex.agent.platform.terminal.policy.InputRequest(
                sessionId = 1L, command = "rm -rf /", bytes = null, owner = InputOwner.AGENT,
            ),
        )
        assertTrue(
            "CRITICAL must be denied before PTY write, got: $decision",
            decision is Decision.Deny,
        )
        assertTrue((decision as Decision.Deny).reason.startsWith("RISK_CRITICAL"))
    }

    @Test
    fun `policy impl keeps allowing read-only and mutating commands`() {
        val impl = TerminalPolicyImpl()
        for (cmd in listOf("ls -la", "echo hi && pwd", "mkdir newdir")) {
            val decision = impl.check(
                com.apex.agent.platform.terminal.policy.InputRequest(
                    sessionId = 1L, command = cmd, bytes = null, owner = InputOwner.AGENT,
                ),
            )
            assertEquals("'$cmd' must stay allowed", Decision.Allow, decision)
        }
    }
}
