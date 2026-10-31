<!-- pr: plugin-market-mcp-registry -->

- MCP 子系统（Spec v1.0 §5，PR-C/5）：远程 server 声明（servers.json 模型 + 条目级校验：非 https 硬门槛 / transport 与 auth 取值域 / id 唯一性，坏声明剔除不拖垮同文件合法端点）+ 注册表引用计数（跨插件共享端点全部卸载才撤、同 id 后装覆盖、空引用集摘 key 防白名单误报）
- AppFunctions 绑定声明（appfunctions.json 模型 + 校验：qualifiedName 点分包名斜杠函数段 / risk 取值域 / toolName 缺省取函数段）+ LocalMcpRegistry 绑定解析（AppFunctions → MCP ToolSpec，AndroidX 接线 M4 落地的纯 JVM 域投影先行）
- 风险等级不允许低报：绑定 risk 与设备侧注册表不一致时取较高档（插件把 R2 说成 R0 骗授权 → 照样 R2 审批），未注册函数折叠 null
- McpToolSanitizer 不可信描述清洗（Spec §5.3 强制约束）：零宽字符/Bidi 劫持/控制字符剥离先于注入检测（防拆词绕过）、超长截断掐指令空间、注入话术标记不删（审计需要，flags 供披露层降档）
- plugin-ci 增至 8/10（+MCP 声明与注册表 / +MCP 工具清洗）
