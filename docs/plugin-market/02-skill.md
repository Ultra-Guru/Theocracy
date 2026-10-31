# Skill 子系统 —— 渐进式披露加载器

> 实现层文档（PR-B）：SKILL.md frontmatter 解析、三阶段披露、
> 完整性校验。代码锚点：`core/plugin-market` 的 `skill/` 包。
> 总纲见 [00-spec.md](00-spec.md) §4。

## 1. 三阶段披露（Spec §4.1）

| 阶段 | API | 拿到什么 | 时机 |
|---|---|---|---|
| Discovery | `SkillLoader.discover(pkg)` | `List<SkillManifest>`（name/description/allowedTools/skillDir）| 安装后 / 会话启动 |
| Activation | `SkillLoader.activate(pkg, manifest)` | `SkillDocument`（完整正文，digest 已复核）| 用户请求匹配 description |
| Execution | `SkillLoader.executionAsset(pkg, manifest, relPath)` | scripts 与 references 单文件字节 | 指令执行到需要时 |

**API 形状即纪律**：`SkillManifest` 没有 body 字段 —— Discovery 阶段
在**类型层**就拿不到正文（渐进式披露不是约定而是不可能违反）。
「用户请求匹配 description」的判定属 agent-engine 提示词层（上层接线），
loader 只提供按 name 精确激活与按相对路径取资产。

## 2. Frontmatter 子集（自研，不引 YAML 依赖）

支持形态：

```markdown
---
name: summarize                       # 必填（缺则该 skill 跳过）
description: 会议纪要整理              # 匹配依据
license: Apache-2.0                   # 可选
allowed-tools: [Read, Write]          # 流式列表（YAML 标准）
allowed-tools:                        # 或短行列表
  - Read
  - Write
---
```

- 裸逗号值是**标量**（YAML 语义）：`description: Hello, world` 不拆列表；
- 定界符必须**整行精确** `---`（正文中的分隔线不误判）；
- 防御式契约：无 frontmatter（全文当正文）/ 未闭合（头部整体忽略 + issue）/
  畸形行（跳过 + issue）/ 孤儿列表项 / 重复键（后值忽略）—— 任何输入吞得下。

## 3. 完整性校验（Spec §4.2）

digest 存于包级 `skills-lock.json`（**不写在 SKILL.md frontmatter 里 ——
digest 行会参与自身计算，自指无解**）：

```json
{"schema": "apex-skills-lock-v1", "digests": {"skills/summarize/SKILL.md": "<sha256hex>"}}
```

信任链分层：

| 层 | 守什么 | 机制 |
|---|---|---|
| 包签名（PR-A） | 整包来源可信（市场分发面） | ES256 + keyring |
| skills-lock | 单 skill 正文未二次改动（安装后本地校验面） | SHA-256 复核，无需 keyring |

- market Ingest 生成 lock 文件（lock 自身在包内 → 被包签名覆盖）；
- `activate` 加载正文前强制复核：声明与实际不符 → `SkillIntegrityException`
  拒绝加载；
- 无 lock 文件（本地开发包）→ 无契约即放行；lock 畸形 → 防御式降级为
  无契约（AppLogger 留痕）；
- digest 比较大小写不敏感（hex 大小写市场后端工具差异容忍）。

## 4. 防御式纪律

- 缺 name / name 重复的 skill **跳过**（issues 留痕），不拖垮同包兄弟；
- `executionAsset` 路径守卫：`../` 穿越、绝对路径、反斜杠、SKILL.md 自身
  （正文只能走 activate）一律 null；
- 虚拟钟注入（`clock: () -> Long`）：审计时间戳不依赖墙钟（仓库测试纪律）。

## 5. 测试锚点（plugin-ci 5-6/10）

| 测试类 | 覆盖 |
|---|---|
| SkillFrontmatterTest | 合法子集 / 流式与短行列表 / 无头部 / 未闭合 / 畸形行 / CRLF |
| SkillLoaderProgressiveTest | 三阶段可见性（类型层无 body）/ 防御式跳过 / 重名保留前者 / 资产守卫 |
| SkillIntegrityTest | lock 注入放行 / 篡改拒绝 / 无 lock 放行 / 畸形 lock 降级 / 大小写 / 跨层算法一致 |
