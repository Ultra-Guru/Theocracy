<!-- pr: plugin-market-skill-loader -->

- Skill 子系统（Spec v1.0 §4，PR-B/5）：渐进式披露三阶段加载器 —— Discovery（frontmatter）/ Activation（完整正文）/ Execution（scripts 与 references 按需），Discovery 返回类型在类型层无 body 字段 —— 渐进式披露由 API 形状保证而非约定
- SKILL.md frontmatter 自研子集解析器（不引 YAML 依赖，防 CVE 攻击面）：流式列表与短行列表双形态、裸逗号值是标量（YAML 语义）、定界符整行精确匹配、无头部/未闭合/畸形行四形态防御式全吞
- Skill 完整性校验（MCP Skills extension 模型）：digest 存于包级 skills-lock.json（不写 frontmatter —— digest 行参与自身计算自指无解），市场 Ingest 生成并被包级 SIGNATURE 覆盖；Activation 前强制复核，声明与实际不符抛 SkillIntegrityException 拒绝加载；无 lock 即无契约放行、畸形 lock 防御式降级
- 防御式纪律：缺 name/重名 skill 跳过不拖垮同包兄弟、executionAsset 路径穿越守卫（../ 与绝对路径与反斜杠与 SKILL.md 自身全拒）、虚拟钟注入
- plugin-ci 检查项增至 6/10（+skill loader 渐进式披露 / +skill 完整性）
