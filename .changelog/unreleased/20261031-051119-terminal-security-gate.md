<!-- pr: terminal-security-gate -->

- SecurityGate 写入前门控（terminal-mcp security 域）：命令进入 PTY 前评估 —— 命令一旦写入 PTY 就无法拦截，拦截点必须在写入前（nl2sh 的 Security → Confirmation → Execution 链式设计）
- 四级决策映射：READ_ONLY / MUTATING 自动放行（事件流分级记录）→ DANGEROUS 人工确认（confirmation_required + confirm_id，命令不执行）→ CRITICAL 拒绝执行（blocked，不可确认绕过）
- 一次性批准票（one-shot approval）：人侧 resolve(approve) 后 120s 内同命令指纹（SHA-256 前 16 hex）重试放行并消费票 —— 长期重放不可能；拒绝仅移除单据
- 待确认单：5 分钟 TTL + 同命令去重（Agent 重试不刷屏，复用 confirmId）+ StateFlow 推流（PR 9/10 控制中心待确认列表的数据源）+ 惰性过期清理
- MCP 接入：run 工具经门控（blocked / confirmation_required / allowed 三态结果，风险级别与理由随结构化结果返回）；gate_status 工具（列表 / 单条查询 / 批准票计数）—— Agent 轮询人侧裁决后重试
- 时钟注入：TTL 语义 JVM 单测零 sleep 验证（12 项：一次性消费、票过期、单据过期、去重、拒绝重开、Flow 同步）
- 工具面 7 工具；run 集成测试 4 项（门控未放行时 runtime 零调用 —— 写入前拦截的铁律断言）
