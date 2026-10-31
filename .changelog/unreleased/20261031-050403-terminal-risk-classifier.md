<!-- pr: terminal-risk-classifier -->

- 四级风险分类器 RiskClassifier（policy 域，纯 Kotlin、零依赖、确定性词法+结构分析，不依赖模型判断）：READ_ONLY（观察类 allowlist + 无写入结构）/ MUTATING（默认桶）/ DANGEROUS（rm -r、chmod 777、dd、写系统目录、pm uninstall、input 注入、find -delete·-exec）/ CRITICAL（写块设备、su/sudo 提权、mkfs 家族、批量删根级路径、设备级重启关机）
- 自研操作符归属感知分段器：重定向（> >> <）片段按写入目标路径定级（/dev/block → CRITICAL、/system 等 → DANGEROUS），管道/链/命令替换（| ; && || ` $(）片段递归分类 —— echo $(rm -rf /) 的替换载荷不漏
- 绕过防护（kelai141/dsh PR#756 教训）：包装器载荷提取跳过恰为 `--` 的词（≤8 次、只跳 `--`）—— trap -- 'rm -rf /sdcard' EXIT 的载荷不被终结符遮蔽；sh 族 -c 载荷 / eval / trap / xargs 尾命令（破坏面工具下限集）/ busybox·toybox applet / env·command·exec·nohup·time·watch 前缀剥离 / find -exec·-ok 递归；嵌套超 4 层保守归 DANGEROUS
- 保守语义（nl2sh 范式）：无法解析或载荷不可见（sh <file>、裸子 shell、空载荷 trap）→ DANGEROUS；heredoc 逐行分类（cat <<EOF 换行 rm -rf / 不漏）
- TerminalPolicyImpl 接入：CRITICAL 在 writePty 前硬拒（RISK_CRITICAL 拒绝理由）—— 与 PR 5/10 的 MCP 门控互为独立的纵深防御；denylist 既有拒绝语义不变
- 共享 fixture 测试矩阵 90+ 用例：命令 → 期望级别的表格形态，PR 5/10 SecurityGate 壳层同构复用（引擎与壳层共享 fixture 是 PR#756 的核心教训）
