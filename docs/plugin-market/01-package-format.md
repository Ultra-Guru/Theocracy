# .agentpkg 插件包格式 v1.0

> 实现层文档（PR-A）：agent-plugin.json 字段表、校验规则表、包级签名。
> 代码锚点：`core/plugin-market` 的 `format/`（manifest）与 `pkg/`（包+签名）。
> 总纲见 [00-spec.md](00-spec.md) §3/§8.3。

## 1. 分层契约

```
形状层  ManifestParser     JSON 文本 → 强类型模型（未知字段忽略、异常折叠 null）
语义层  ManifestValidator  必填性 / 格式 / 取值域 / 路径穿越（ERROR vs WARNING）
包层    PluginPackageReader zip 解包 + zip-slip 防线 + 组件存在性对账
信任层  PluginPackageVerifier ES256 验签 + 逐文件 digest 复核 + 覆盖完整性
```

每层失败都不抛异常（防御式 IO）：折叠为 `Failure(issues)` ——
市场后台与安装向导一次展示全部问题。

## 2. agent-plugin.json 字段表

字段名以 `ManifestSchema.Field` 常量为唯一真相源（本表与之逐一对齐；
plugin-ci 第 10 项检查做文档↔代码一致性核对）。

| 字段 | 类型 | 必填 | 规则（违反级别） |
|---|---|---|---|
| schemaVersion | String | ✅ | 必须为 "1.0"（ERROR: SCHEMA_VERSION） |
| id | String | ✅ | 反向域名，如 com.example.meeting-helper（ERROR: ID_EMPTY/ID_FORMAT） |
| name | String | ✅ | slug：小写字母数字+连字符（ERROR: NAME_EMPTY/NAME_FORMAT） |
| displayName | String | ➖ | 缺省回落 name（WARNING: DISPLAY_NAME_MISSING） |
| version | String | ✅ | 语义化 x.y.z 可选先行/构建段（ERROR: VERSION_EMPTY/VERSION_FORMAT） |
| description | String | ➖ | — |
| author | {name, email} | ➖ | — |
| license | String | ➖ | SPDX 标识（格式层不校验） |
| category | String | ➖ | 白名单 productivity/development/…/other（WARNING: CATEGORY_UNKNOWN） |
| tags | List | ➖ | ≤12 个（WARNING: TAGS_OVERFLOW） |
| minAndroidApi | Int | ✅ | ≥21（ERROR）；<34 警告 AppFunctions 不可用（WARNING） |
| compat.claudeMarketplace | Boolean | ➖ | 来源标记（Claude marketplace 摄入置 true） |
| compat.opencodePlugin | Boolean | ➖ | 含 OpenCode hook 组件 |
| components.skills | List | ➖ | skill 目录相对路径（ERROR: 路径穿越；对账须存在） |
| components.mcpServers | String | ➖ | servers.json 路径（可执行组件 → capabilities 必填） |
| components.appFunctions | String | ➖ | appfunctions.json 路径 |
| components.hooks | String | ➖ | hooks 目录路径 |
| capabilities[].id | String | 条件 | 点分层如 calendar.read；不可重复（ERROR） |
| capabilities[].risk | String | 条件 | R0/R1/R2/R3（ERROR: CAP_RISK_INVALID） |
| capabilities[].description | String | ➖ | 建议补齐（WARNING） |
| dependencies | List | ➖ | 插件 id 列表（首版仅记录） |
| signature.algorithm | String | ➖ | 唯一合法 ES256（ERROR） |
| signature.keyId | String | ➖ | 市场 keyring 索引 |

组件路径规则（格式层防穿越）：不以 `/` 开头、无 `..` 段、无反斜杠、
无驱动器冒号、无 NUL。skills/hooks 是目录（前缀对账），mcpServers/
appFunctions 是文件（精确对账）。

## 3. 包级签名（SIGNATURE 文件）

- **算法**：ES256（ECDSA P-256 + SHA-256，JOSE raw r‖s 64 字节，
  base64url 无 padding）。JCA 侧做 DER↔raw 双向转换（`Es256Verifier`）。
- **keyring 信任模型**：验签公钥来自市场预置 keyring（keyId → X.509
  base64）；包内不携带公钥（自证无效）；keyId 不在 keyring → 拒绝。
- **canonical 载荷**（确定性序列化，跨端可复现）：

```
agentpkg-signature-v1
algorithm=ES256
keyId=<keyId>
file:<path>=<sha256hex>     ← 包内全部文件（SIGNATURE 自身除外），按 path 字典序
```

- **校验链**（`PluginPackageVerifier`，顺序即安全语义）：
  1. SIGNATURE 存在且可解析（REJECTED: SIGNATURE_MISSING / SIGNATURE_INVALID）
  2. algorithm == ES256、keyId 非空（ALGORITHM_UNSUPPORTED / KEY_ID_MISSING）
  3. canonical 验签（SIGNATURE_CRYPTO_FAILED —— 含未知 keyId/坏 key/坏编码/失配）
  4. 逐文件 digest 复核（DIGEST_MISMATCH —— 签名有效但内容被换）
  5. 覆盖完整性（FILE_COVERAGE_INCOMPLETE —— 漏签文件视作篡改面）

**篡改语义**：改 manifest 或任何内容文件任一字节 → 第 3 或第 4 步必然
失败（canonical 含 digest 清单 + 清单对包内文件复核，双保险）。

## 4. zip 安全防线

- **zip-slip**：entry 名含 `..` 段 / 绝对路径 / 反斜杠 / 盘符 / NUL →
  整包拒绝（不静默跳过单 entry —— 半包比拒绝更难排查）。
- **体积**：包 ≤32MB（PKG_TOO_LARGE）；单 entry 解压 ≤16MB（zip 炸弹防线）。
- **垃圾输入**：ZipInputStream 对非 zip 数据静默零条目（实测行为）→
  PKG_EMPTY 拒绝；截断流在 entry 读取期抛 → PKG_CORRUPT。

## 5. 测试锚点（plugin-ci 1-4/10）

| 测试类 | 覆盖 |
|---|---|
| ManifestParserTest | Spec 样例全字段 / 未知字段 / 畸形折叠 / round-trip |
| ManifestValidatorTest | 全 ERROR/WARNING 规则正反例 / 组件对账 / 前缀归一 |
| PluginPackageReaderTest | round-trip / zip-slip 五形态 / 体积防线 / 组件缺失 |
| Es256SignatureRoundTripTest | 签名正例 / 五种篡改拒绝 / keyring 信任模型 / DER↔raw 内核 |

测试基建 `PackageBuilder`（test sourceset）：内存构建 .agentpkg +
ES256 签名 round-trip —— 生产签名走市场后端 CLI，不进 main。
