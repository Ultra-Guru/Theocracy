<!-- pr: plugin-market-ingest -->

- 生态摄入兼容（Spec v1.0 §6/§9，PR-E/5 收官）：Claude Code marketplace 镜像源摄入 —— marketplace.json 条目映射 agent-plugin.json（元数据保留 / id 合成 claude-marketpace 前缀隔离 / author 双形态提取），源格式无风险声明时保守合成 R2 capability（绝不自动执行），摄入产物必须过本仓 ManifestValidator（镜像源不豁免门禁）
- OpenCode 移动端适配层：package.json opencode 入口识别（对象/字符串双形态），普通 npm 包不摄入（不伪装适配）；compat.opencodePlugin 标注 + hooks 细分 R2 合成
- HookAdapterContract 兼容契约冻结（M6 JS 引擎接线前先行）：支持 tool.execute.before/after 与 session.created/idle，tui.* 与 shell.env 显式不支持（详情页「移动端不可用」标注），沙箱四边界（无网络/无文件系统/仅宿主桥接 API/无环境变量）
- PluginShapeBadges 形态标签严格推导（components+compat 推导而非独立声明 —— 防声明与内容漂移的欺骗面）：SKILLS/MCP/APPFUNCTIONS/OPENCODE_HOOK/EMPTY + Spec §4.3 三分类判定
- plugin-ci 收齐 10/10：+第 10 项「生态摄入兼容 + 文档一致性」（ingest 测试 + check_plugin_docs_sync.py —— docs 字段表与 ManifestSchema 常量双向对齐，核心必填五字段在册性检查）
