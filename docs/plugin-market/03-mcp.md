# MCP 子系统 —— 远程声明 + AppFunctions 本地注册表

> 实现层文档（PR-C）。代码锚点：`core/plugin-market` 的 `mcp/` 包。
> 总纲见 [00-spec.md](00-spec.md) §5。

## 1. 两类来源的域模型

| 类 | 职责 | 数据源 |
|---|---|---|
| `McpServerDeclaration` + `McpServerDeclarationCodec` | 远程 server 声明模型与解析校验 | mcp/servers.json |
| `McpServerRegistry` | 已装插件声明的远程端点登记（域名白名单数据源）| install/uninstall 生命周期 |
| `AppFunctionBinding` + `AppFunctionBindingCodec` | 本地设备函数绑定声明 | mcp/appfunctions.json |
| `LocalMcpRegistry` | AppFunctions → MCP ToolSpec 暴露（系统注册表域投影）| register/resolveBinding |
| `McpToolSanitizer` | 不可信 tool description 清洗 | 所有第三方描述 |

AndroidX AppFunctions 的真实接线（KSP + 跨进程）在里程碑 M4 app 层落地；
本层是纯 JVM 可测的域模型先行（注册/解析/冲突契约先冻结）。

## 2. 远程声明硬门槛（Spec §5.1 安全约束）

- **url 必须 https 起头**（SSE 传输也是 https 通道）—— 非 https 整条剔除；
- transport ∈ {https, sse}；auth.type ∈ {none, oauth, apikey}；
- id 非空且包内唯一；
- 条目级剔除（不是整文件拒绝）—— 一个坏声明不拖垮同文件合法端点。

## 3. 注册表引用计数

- 跨插件声明同一 url = 共享端点：**引用计数**，任一持有者卸载不撤端点，
  全部卸载才撤（返回被撤 id 供连接层断链）；
- 同 id 后装覆盖先装（系统注册表同语义），旧 url 引用清空即摘 key
  （防空集合让白名单查询误报 —— 已有回归测试钉住）。

## 4. AppFunctions 绑定解析（Spec §5.2）

- qualifiedName 形如 `com.example.calendar/createEvent`；toolName 缺省取
  函数段，显式给定时须合法工具名；
- **风险等级不允许低报**：绑定 risk 与注册表（设备侧声明）不一致时
  **取较高档**（插件把 R2 说成 R0 骗授权 → 照样 R2 审批）；
- 未注册函数 → null（上层折叠为「该能力不可用」）。

## 5. 不可信描述清洗（Spec §5.3 强制）

威胁模型：第三方描述会被拼进 Agent 工具目录上下文 —— 提示注入面。

1. **剥离先于检测**：零宽字符（U+200B 族）/ Bidi 劫持（U+202A-202E）/
   控制字符先剥离 —— 拆词绕过模式匹配的攻击失效；
2. **截断**：超 2000 字符截断（掐掉藏在长尾的指令空间）；
3. **注入话术标记不删**（审计需要）：ignore previous / disregard /
   伪角色标记 `System:` / 角色劫持 / 提示词钓鱼 / 越狱话术 ——
   flags 供披露层警示与信任降档（执行管线接线，PR-D）。

## 6. 测试锚点（plugin-ci 7-8/10）

| 测试类 | 覆盖 |
|---|---|
| McpDeclarationTest | servers.json 解析 / 非 https 硬门槛 / 重复与空 id / auth 域 / 引用计数共享端点 / 覆盖语义 / appfunctions 校验 |
| LocalMcpRegistryTest | 绑定解析 / 未注册 null / 风险取高档（双向）/ 描述回落 / 清洗（注入标记 / 零宽剥离先于检测 / Bidi / 截断 / 干净通过） |
