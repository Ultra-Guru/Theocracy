package com.apex.agent.platform.terminal.policy

// enum 常量需显式导入（同文件顶层 enum 的条目也不会自动进入作用域）
import com.apex.agent.platform.terminal.policy.RiskLevel.CRITICAL
import com.apex.agent.platform.terminal.policy.RiskLevel.DANGEROUS
import com.apex.agent.platform.terminal.policy.RiskLevel.MUTATING
import com.apex.agent.platform.terminal.policy.RiskLevel.READ_ONLY

/**
 * T-RISK — 四级命令风险分类器（terminal 十连 PR 4/10）。
 *
 * ## 定位
 * 纯本地、确定性、词法 + 结构分析 —— **不依赖模型判断**（nl2sh 范式：
 * 模型只提案，本地程序裁定）。输出四级语义，供安全门控（PR 5/10）与
 * 审计事件流消费：
 *
 * - [READ_ONLY]   观察类（ls / cat / grep / dumpsys …）且无写入结构 → 静默放行
 * - [MUTATING]    默认桶：改变状态但破坏面有限（mkdir / git commit / am start …）→ 自动放行 + 高亮
 * - [DANGEROUS]   破坏面显著（rm -r / chmod 777 / dd / 写系统目录 / pm uninstall / input 注入）→ 人工确认
 * - [CRITICAL]    不可逆灾难级（写块设备 / 提权 / 格式化 / 批量删根级路径）→ 拒绝执行
 *
 * 无法解析或动态执行（`sh <file>` / 空载荷包装器）→ 保守归 DANGEROUS。
 *
 * ## 结构分析（自研分段器，操作符归属感知）
 * 复用 [CommandParser.splitSegments] 的引号感知思想，但额外记录**切分操作符**：
 * 重定向（> >> <）后的片段是「写入目标」（按路径定级），管道/链/替换
 * （| ; && || ` $(）后的片段是「命令」（递归分类）。由此：
 * - `cat f | grep x` → 两段全 READ_ONLY → READ_ONLY
 * - `grep x > /system/y` → 重定向目标 /system → DANGEROUS（不是 MUTATING）
 * - `echo $(rm -rf /)` → 命令替换自成一段 → 递归命中 CRITICAL
 * - `cat <<EOF` 换行 `rm -rf /` → 逐行分类，heredoc 体内的破坏命令不漏
 *
 * ## 绕过防护（kelai141/dsh PR#756 教训）
 * 包装器载荷提取时**跳过恰为 `--` 的词**（≤8 次；只跳 `--` 不跳其他 `-` 词，
 * 否则 `sh -c -x 'cmd'` 反丢真载荷）：`trap -- 'rm -rf /x' EXIT` 的载荷
 * 是 rm 而非 `--`。包装器集合：sh 族 -c 载荷 / eval / trap / xargs 尾命令 /
 * env·command·exec·nohup·time·strace·watch 前缀剥离 / busybox·toybox applet /
 * find -exec·-ok·-delete。递归深度上限 [MAX_DEPTH]，超出保守 DANGEROUS。
 *
 * ## 与既有策略层的关系
 * [CommandPolicy]（黑白名单）继续管「用户策略」；本分类器管「风险语义」。
 * [TerminalPolicyImpl] 接入点：CRITICAL 在 writePty 前硬拒（纵深防御，
 * 与 MCP 门控（PR 5/10）独立 —— 门控被绕过时这里仍兜底）。
 */
class RiskClassifier {

    // ─── 对外入口 ───

    fun classify(command: String): RiskAssessment {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return RiskAssessment(READ_ONLY, "no-op: empty command")
        var worst = READ_ONLY
        var reason = "no risk signals"
        for (line in command.split('\n', '\r')) {
            for (piece in segment(line)) {
                val (level, why) = when (piece) {
                    is RedirectPiece -> redirectRisk(piece)
                    is CommandPiece -> commandRisk(piece.text, depth = 0)
                }
                if (level > worst) {
                    worst = level
                    reason = why
                }
            }
        }
        return RiskAssessment(worst, reason)
    }

    // ─── 分段器（操作符归属感知）───

    private sealed interface Piece

    private data class CommandPiece(val text: String) : Piece

    private data class RedirectPiece(val target: String, val append: Boolean) : Piece

    private fun segment(line: String): List<Piece> {
        val out = mutableListOf<Piece>()
        val current = StringBuilder()
        var inQuote: Char? = null
        var expectRedirectTarget = false
        var append = false
        var i = 0
        fun flushCommand() {
            if (current.isNotBlank()) out.add(CommandPiece(current.toString()))
            current.clear()
        }
        fun flushRedirect() {
            // 重定向目标 = 片段首 token（`> file extra` 的 extra 属命令侧残词，随目标片段走保守判定）
            if (current.isNotBlank()) out.add(RedirectPiece(current.toString().trim(), append))
            current.clear()
            expectRedirectTarget = false
        }
        while (i < line.length) {
            val c = line[i]
            val two = if (i + 2 <= line.length) line.substring(i, i + 2) else ""
            when {
                inQuote != null -> {
                    current.append(c)
                    if (c == '\\' && inQuote == '"') {
                        line.getOrNull(i + 1)?.let(current::append)
                        i += 2
                        continue
                    }
                    if (c == inQuote) inQuote = null
                }
                c == '"' || c == '\'' -> {
                    inQuote = c
                    current.append(c)
                }
                c == '\\' -> {
                    current.append(c)
                    line.getOrNull(i + 1)?.let(current::append)
                    i += 2
                    continue
                }
                two == "&&" || two == "||" || two == ">>" || two == "$(" || two == "<<" -> {
                    if (expectRedirectTarget) flushRedirect()
                    else flushCommand()
                    if (two == ">>") {
                        expectRedirectTarget = true
                        append = true
                    }
                    i += 2
                    continue
                }
                c == '>' -> {
                    flushCommand()
                    expectRedirectTarget = true
                    append = false
                }
                c == '<' -> {
                    flushCommand()
                    expectRedirectTarget = true
                    append = false
                }
                c == ';' || c == '|' || c == '&' || c == '`' || c == '(' -> {
                    if (expectRedirectTarget) flushRedirect()
                    else flushCommand()
                }
                else -> current.append(c)
            }
            i++
        }
        if (expectRedirectTarget) flushRedirect() else flushCommand()
        return out
    }

    // ─── 重定向目标定级 ───

    private fun redirectRisk(piece: RedirectPiece): RiskAssessment {
        val path = piece.target.split(CommandParser.WHITESPACE).firstOrNull() ?: ""
        return when {
            path.startsWith("/dev/block") || path.startsWith("/dev/sd") ->
                RiskAssessment(CRITICAL, "redirect writes raw block device: $path")
            path.startsWith("/system") || path.startsWith("/vendor") || path.startsWith("/proc") ||
                path.startsWith("/sys") || path.startsWith("/etc") ->
                RiskAssessment(DANGEROUS, "redirect writes system path: $path")
            else -> RiskAssessment(MUTATING, "redirect writes file: $path")
        }
    }

    // ─── 命令定级 ───

    private fun commandRisk(text: String, depth: Int): RiskAssessment {
        if (depth > MAX_DEPTH) return RiskAssessment(DANGEROUS, "wrapper nesting exceeds $MAX_DEPTH levels")
        var rest = tokenize(text)
        // 剥离前导 VAR=VAL 赋值（无执行语义）
        while (rest.isNotEmpty() && ENV_ASSIGNMENT.matches(rest.first())) rest = rest.drop(1)
        if (rest.isEmpty()) return RiskAssessment(READ_ONLY, "no executable token")
        // 命令替换残留的右括号清尾（分段器不切 `)`）
        val cleaned = rest.mapIndexed { idx, t -> if (idx == rest.lastIndex) t.trimEnd(')') else t }
        val exe = CommandParser.basename(cleaned.first()).lowercase().ifEmpty { cleaned.first().lowercase() }
        val args = cleaned.drop(1)
        return dispatch(exe, args, depth)
    }

    private fun dispatch(exe: String, args: List<String>, depth: Int): RiskAssessment = when {
        exe in CRITICAL_TOOLS -> RiskAssessment(CRITICAL, "'$exe' is a critical-risk tool (format/privilege/device)")
        exe.startsWith("mkfs") -> RiskAssessment(CRITICAL, "'$exe' formats a filesystem")
        exe in WRAPPER_EXEC -> wrapperRisk(exe, args, depth)
        else -> simpleRisk(exe, args, depth)
    }

    private fun simpleRisk(exe: String, args: List<String>, depth: Int): RiskAssessment = when (exe) {
        "rm", "unlink", "rmdir", "shred", "truncate" -> {
            val recursive = args.any { it.startsWith("-") && !it.startsWith("--") && it.contains('r') } ||
                args.contains("--recursive")
            val targets = args.filter { !it.startsWith("-") }
            val rootLevel = targets.any { isRootLevelPath(it) }
            when {
                exe == "rm" && recursive && rootLevel ->
                    RiskAssessment(CRITICAL, "rm -r on root-level path: ${targets.first()}")
                exe != "rm" && rootLevel ->
                    RiskAssessment(DANGEROUS, "'$exe' targets root-level path: ${targets.first()}")
                exe == "rm" && recursive && targets.isEmpty() ->
                    RiskAssessment(DANGEROUS, "rm -r with runtime-supplied targets (xargs/interpolation)")
                exe == "rm" && recursive -> RiskAssessment(DANGEROUS, "rm -r deletes directory trees")
                else -> RiskAssessment(MUTATING, "'$exe' deletes files")
            }
        }
        "chmod", "chown", "chgrp" -> {
            val recursive = args.any { it.startsWith("-") && (it.contains('R') || it.contains('r')) } ||
                args.contains("--recursive")
            val nonFlags = args.filter { !it.startsWith("-") }
            // chmod 的首个非旗标参是 mode（777 等）；chown/chgrp 是 owner spec —— 丢弃后剩目标
            val worldWritable = exe == "chmod" && nonFlags.firstOrNull()?.let { isWorldWritableMode(it) } == true
            val targets = nonFlags.drop(1)
            val rootLevel = targets.any { isRootLevelPath(it) }
            when {
                recursive && rootLevel -> RiskAssessment(CRITICAL, "'$exe' -R on root-level path")
                worldWritable && rootLevel -> RiskAssessment(CRITICAL, "'$exe' world-writable on root-level path")
                worldWritable || recursive -> RiskAssessment(DANGEROUS, "'$exe' world-writable/recursive mode change")
                else -> RiskAssessment(MUTATING, "'$exe' changes file metadata")
            }
        }
        "dd" -> {
            val of = args.firstOrNull { it.startsWith("of=") }?.removePrefix("of=")
            when {
                of != null && (of.startsWith("/dev/block") || of.startsWith("/dev/sd")) ->
                    RiskAssessment(CRITICAL, "dd writes raw block device: $of")
                else -> RiskAssessment(DANGEROUS, "dd raw copy (of=${of ?: "stdout"})")
            }
        }
        "wipefs", "fdisk", "parted", "flash_image", "make_f2fs", "mke2fs" ->
            RiskAssessment(CRITICAL, "'$exe' rewrites disk/partition structures")
        "su", "sudo", "magisk", "su_root" -> RiskAssessment(CRITICAL, "'$exe' escalates privileges")
        "reboot", "shutdown", "halt", "poweroff" -> RiskAssessment(CRITICAL, "'$exe' disrupts the device")
        "pm" -> when (args.firstOrNull()) {
            "list", "path", "dump", "query" -> RiskAssessment(READ_ONLY, "pm ${args.firstOrNull()} reads package state")
            "uninstall", "clear", "disable", "hide" -> RiskAssessment(DANGEROUS, "pm ${args.firstOrNull()} removes app state")
            else -> RiskAssessment(MUTATING, "pm subcommand")
        }
        "settings" -> when (args.firstOrNull()) {
            "get", "list", "list_all" -> RiskAssessment(READ_ONLY, "settings reads")
            "put", "delete", "reset" ->
                if (args.getOrNull(1) == "secure" || args.getOrNull(1) == "global") {
                    RiskAssessment(DANGEROUS, "settings ${args.getOrNull(1)} changes device policy")
                } else {
                    RiskAssessment(MUTATING, "settings write")
                }
            else -> RiskAssessment(MUTATING, "settings subcommand")
        }
        "am" -> when (args.firstOrNull()) {
            "force-stop", "kill" -> RiskAssessment(DANGEROUS, "am ${args.firstOrNull()} terminates apps")
            else -> RiskAssessment(MUTATING, "am subcommand")
        }
        "input" -> RiskAssessment(DANGEROUS, "input injects UI events (nl2sh paradigm)")
        "kill", "killall", "pkill" -> RiskAssessment(DANGEROUS, "'$exe' terminates processes")
        "mount", "umount" -> RiskAssessment(DANGEROUS, "'$exe' changes filesystem mounts")
        "git" -> when (args.firstOrNull()) {
            "status", "log", "diff", "show", "branch", "blame", "remote", "rev-parse", "ls-files" ->
                RiskAssessment(READ_ONLY, "git ${args.firstOrNull()} reads repository")
            "clean" -> if (args.any { it == "-f" || it.startsWith("-f") || it.startsWith("-fd") || it.startsWith("-df") }) {
                RiskAssessment(DANGEROUS, "git clean -f discards untracked files")
            } else {
                RiskAssessment(MUTATING, "git clean")
            }
            "reset" -> if (args.contains("--hard")) {
                RiskAssessment(DANGEROUS, "git reset --hard discards working tree")
            } else {
                RiskAssessment(MUTATING, "git reset")
            }
            else -> RiskAssessment(MUTATING, "git subcommand")
        }
        "find" -> {
            if (args.contains("-delete")) {
                RiskAssessment(DANGEROUS, "find -delete removes matched trees")
            } else if (args.contains("-exec") || args.contains("-ok") || args.contains("-execdir")) {
                val payload = args.dropWhile { it != "-exec" && it != "-ok" && it != "-execdir" }.drop(1)
                    .takeWhile { it != ";" && it != "+" }
                val inner = commandRisk(payload.joinToString(" "), depth + 1)
                // find 范围遍历 + 逐个执行：inner 级别上探到至少 DANGEROUS
                if (inner.level >= DANGEROUS) inner
                else RiskAssessment(DANGEROUS, "find -exec runs '${payload.joinToString(" ").take(40)}' on every match")
            } else {
                RiskAssessment(READ_ONLY, "find searches")
            }
        }
        "sed" -> if (args.any { it == "-i" || it.startsWith("-i") }) {
            RiskAssessment(MUTATING, "sed -i edits files in place")
        } else {
            RiskAssessment(READ_ONLY, "sed streams")
        }
        "logcat" -> if (args.contains("-c") || args.contains("--clear")) {
            RiskAssessment(MUTATING, "logcat -c clears ring buffers")
        } else {
            RiskAssessment(READ_ONLY, "logcat reads")
        }
        else -> if (exe in READ_ONLY_TOOLS) {
            RiskAssessment(READ_ONLY, "'$exe' is an observation tool")
        } else {
            RiskAssessment(MUTATING, "'$exe' is not in the read-only allowlist (default bucket)")
        }
    }

    // ─── 包装器载荷 ───

    private fun wrapperRisk(exe: String, args: List<String>, depth: Int): RiskAssessment = when (exe) {
        "sh", "bash", "zsh", "dash", "ash", "ksh", "mksh" -> {
            val cIndex = args.indexOfFirst { it == "-c" || it.startsWith("-c") }
            val payload = if (cIndex >= 0 && cIndex + 1 < args.size) {
                args[cIndex + 1]
            } else {
                // 无 -c：`sh script`（脚本内容不可见）或裸子 shell —— 动态执行保守归高危
                return RiskAssessment(DANGEROUS, "'$exe' without -c payload (script/subshell: unanalyzable)")
            }
            val inner = classify(payload)
            if (inner.level >= DANGEROUS) inner else RiskAssessment(inner.level, "'$exe' -c wraps: ${inner.reason}")
        }
        "eval" -> {
            val payload = args.joinToString(" ")
            if (payload.isBlank()) RiskAssessment(DANGEROUS, "eval with empty payload")
            else classify(payload)
        }
        "trap" -> {
            // PR#756：跳过恰为 `--` 的词（≤8 次且只跳 `--`）再取载荷
            val payload = args.dropWhileIndexed { idx, token -> idx < 8 && token == "--" }
                .firstOrNull { it != "--" }
            if (payload == null) RiskAssessment(DANGEROUS, "trap payload not statically visible")
            else classify(payload)
        }
        "xargs" -> {
            val tail = args.dropWhile { it.startsWith("-") || it == "--" }
            if (tail.isEmpty()) return RiskAssessment(READ_ONLY, "xargs with default echo")
            val tailExe = CommandParser.basename(tail.first()).lowercase()
            val inner = commandRisk(tail.joinToString(" "), depth + 1)
            when {
                inner.level >= DANGEROUS -> inner
                tailExe in XARGS_DESTRUCTIVE_FLOOR ->
                    RiskAssessment(DANGEROUS, "xargs runs '$tailExe' on runtime-supplied items")
                else -> RiskAssessment(MUTATING, "xargs runs '$tailExe' on runtime items")
            }
        }
        "busybox", "toybox" -> {
            val applet = args.firstOrNull { !it.startsWith("-") && it != "--" }
            if (applet == null) RiskAssessment(MUTATING, "'$exe' without applet")
            else commandRisk((listOf(applet) + args.drop(1)).joinToString(" "), depth + 1)
        }
        else -> {
            // env / command / exec / nohup / time / strace / ltrace / watch：前缀剥离后递归
            val rest = args.dropWhile { it.startsWith("-") || it == "--" || ENV_ASSIGNMENT.matches(it) }
            if (rest.isEmpty()) RiskAssessment(READ_ONLY, "'$exe' with no payload command")
            else commandRisk(rest.joinToString(" "), depth + 1)
        }
    }

    // ─── 工具集 ───

    private fun tokenize(s: String): List<String> {
        val cleaned = StringBuilder()
        var inQuote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                inQuote != null -> {
                    if (c == '\\' && inQuote == '"') {
                        s.getOrNull(i + 1)?.let(cleaned::append)
                        i += 2
                        continue
                    }
                    if (c == inQuote) inQuote = null else cleaned.append(c)
                }
                c == '"' || c == '\'' -> inQuote = c
                c == '\\' -> {
                    s.getOrNull(i + 1)?.let(cleaned::append)
                    i += 2
                    continue
                }
                else -> cleaned.append(c)
            }
            i++
        }
        return cleaned.toString().split(CommandParser.WHITESPACE).filter { it.isNotBlank() }
    }

    private fun isWorldWritableMode(mode: String): Boolean {
        val digits = mode.filter { it.isDigit() }
        return digits.length == 3 && digits[2] in '6'..'7'
    }

    private fun isRootLevelPath(p: String): Boolean {
        val t = p.trimEnd('/')
        if (t.isEmpty()) return true // "/"
        if (t == "/*") return true
        return BROAD_PATHS.any { t == it || t == "$it/*" }
    }

    /** 带索引的 dropWhile（trap 的 `--` 跳过需要「前 8 个」上界）。 */
    private inline fun <T> List<T>.dropWhileIndexed(predicate: (Int, T) -> Boolean): List<T> {
        var i = 0
        while (i < size && predicate(i, this[i])) i++
        return drop(i)
    }

    companion object {
        const val MAX_DEPTH = 4

        private val ENV_ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

        /** 不可逆灾难级工具（不经参数判断直接 CRITICAL）。 */
        val CRITICAL_TOOLS = setOf("su", "sudo", "magisk", "reboot", "shutdown", "halt", "poweroff")

        /** 根级破坏面路径（rm/chmod 的 CRITICAL 判定基准）。 */
        val BROAD_PATHS = setOf(
            "/", "/sdcard", "/storage", "/data", "/system", "/vendor", "/etc", "/proc", "/sys",
        )

        /** 观察类 allowlist（ReadOnly 桶；写形态由分段器/旗标分析另行定级）。cd 属
         *  shell 内导航（不改文件系统），归入观察类 —— 否则 `cd /x && ls` 这类最
         *  常见的 Agent 链式命令会被误判为 MUTATING。 */
        val READ_ONLY_TOOLS = setOf(
            "ls", "cat", "pwd", "echo", "printf", "grep", "egrep", "fgrep", "head", "tail", "wc",
            "which", "whoami", "id", "uname", "hostname", "printenv", "date", "uptime", "sleep",
            "stat", "file", "du", "df", "ps", "free", "top", "getprop", "dumpsys", "true", "false",
            "test", "md5sum", "sha1sum", "sha256sum", "cksum", "cut", "sort", "uniq", "tr", "ping",
            "netstat", "ifconfig", "ip", "readlink", "realpath", "basename", "dirname", "groups", "cd",
        )

        /** 载荷型包装器（dispatch 优先于 simpleRisk）。 */
        val WRAPPER_EXEC = setOf(
            "sh", "bash", "zsh", "dash", "ash", "ksh", "mksh",
            "eval", "trap", "xargs", "busybox", "toybox",
            "env", "command", "exec", "nohup", "time", "strace", "ltrace", "watch",
        )

        /** xargs 尾命令的破坏面下限集（目标来自 stdin 运行时注入）。 */
        val XARGS_DESTRUCTIVE_FLOOR = setOf(
            "rm", "unlink", "rmdir", "shred", "truncate",
            "chmod", "chown", "chgrp", "dd", "kill", "killall", "pkill",
            "sh", "bash", "zsh", "dash", "mksh", "eval",
        )
    }
}

/** 风险四级（决策语义见 [RiskClassifier] KDoc）。 */
enum class RiskLevel { READ_ONLY, MUTATING, DANGEROUS, CRITICAL }

/** 一次分类结果：级别 + 人类/Agent 可读的理由（事件流审计直接引用）。 */
data class RiskAssessment(
    val level: RiskLevel,
    val reason: String,
)
