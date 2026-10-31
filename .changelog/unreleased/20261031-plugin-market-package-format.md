<!-- pr: plugin-market-package-format -->

- 插件市场域基座（Spec v1.0 §3/§8.3，PR-A/5）：新纯 JVM 模块 core:plugin-market —— agent-plugin.json 完整数据模型 + 防御式解析（未知字段忽略/异常折叠）+ 语义校验器（必填/格式/风险取值域/路径穿越，ERROR 与 WARNING 分级）
- .agentpkg 解包器：zip-slip 整包拒绝（父段穿越/绝对路径/盘符/NUL/反斜杠）+ 体积双防线（包 32MB/单 entry 16MB）+ manifest 组件存在性对账（声明目录与文件在包内必须真实存在）
- ES256 包级签名：JOSE raw r‖s 编码 + JCA DER 双向转换内核 + 市场 keyring 信任模型（keyId 索引，包内不携带公钥）+ canonical 确定性载荷 + 逐文件 digest 复核 + 漏签覆盖检查（签名有效但内容被换 / 清单外私货文件均拒绝）
- plugin-ci.yml 专用检查项上线（终态 10 项，本 PR 1-4：manifest 解析 / schema 校验 / 打包签名 / 编译预算）+ 共享 composite action（JDK/SDK/Gradle 装配收敛为单份）
- ci.yml 接线新模块：static-analysis 增编译步骤 / unit-tests 增 :core:plugin-market:test；文档 docs/plugin-market/00-spec.md（总纲归档）+ 01-package-format.md（字段表/签名链/防线）
