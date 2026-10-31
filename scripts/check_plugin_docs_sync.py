#!/usr/bin/env python3
# ═════════════════════════════════════════════════════════════════════════════
# check_plugin_docs_sync.py — 插件市场 spec 文档 ↔ 代码字段表一致性检查
# ═════════════════════════════════════════════════════════════════════════════
#
# plugin-ci 第 10 项检查的后端：docs/plugin-market/01-package-format.md 的
# 字段表必须与 core/plugin-market 的 ManifestSchema.Field 常量对齐 ——
# 字段表是插件开发者与市场后端的对接契约，代码漂移文档不跟（或反之）
# 都是真实事故面（Ingest 按文档产 manifest，运行时按代码校验）。
#
# 检查规则（双向）：
#   1. docs 字段表中出现的每个字段名（点路径取末段）必须存在于
#      ManifestSchema.kt 的字符串常量值集合；
#   2. 核心必填字段（schemaVersion/id/name/version/minAndroidApi）必须
#      在 docs 字段表中有行 —— 没写文档的关键字段等于没发布。
#
# 退出码：0 = 一致；1 = 有漂移（打印全部差异）。
# ═════════════════════════════════════════════════════════════════════════════
import re
import sys
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "core/plugin-market/src/main/kotlin/com/apex/agent/plugin/market/format/ManifestSchema.kt"
DOCS = ROOT / "docs/plugin-market/01-package-format.md"

# 核心必填字段（docs 表必须有行）
CORE_FIELDS = {"schemaVersion", "id", "name", "version", "minAndroidApi"}


def schema_constants() -> set:
    """提取 ManifestSchema.kt 内 const val XXX = "value" 的全部 value。"""
    text = SCHEMA.read_text(encoding="utf-8")
    return set(re.findall(r'const val [A-Z_]+ = "([^"]+)"', text))


def doc_table_fields() -> set:
    """提取字段表首列字段名 —— 只认「| 字段 |」表头的表格（测试锚点表不抓）。"""
    fields = set()
    in_field_table = False
    for line in DOCS.read_text(encoding="utf-8").splitlines():
        if re.match(r"^\|\s*字段\s*\|", line):
            in_field_table = True
            continue
        if in_field_table:
            if not line.startswith("|"):
                in_field_table = False  # 表格结束
                continue
            m = re.match(r"^\|\s*`?([a-zA-Z][a-zA-Z0-9_.]*)`?\s*\|", line)
            if m and m.group(1) != "字段":
                fields.add(m.group(1).split(".")[-1])
    return fields


def main() -> int:
    if not SCHEMA.is_file():
        print(f"✗ 缺少 schema 源: {SCHEMA}")
        return 1
    if not DOCS.is_file():
        print(f"✗ 缺少文档: {DOCS}")
        return 1

    consts = schema_constants()
    fields = doc_table_fields()
    fail = False

    unknown = sorted(f for f in fields if f not in consts)
    if unknown:
        print("❌ docs 字段表出现 ManifestSchema 未定义的字段名（文档超前或写错）:")
        for f in unknown:
            print(f"   - {f}")
        fail = True

    missing = sorted(c for c in CORE_FIELDS if c not in fields)
    if missing:
        print("❌ 核心必填字段未在 docs 字段表出现（契约未发布）:")
        for f in missing:
            print(f"   - {f}")
        fail = True

    if not fail:
        print(f"✅ 插件市场文档 ↔ 代码字段表一致（docs {len(fields)} 字段 / "
              f"schema {len(consts)} 常量，核心 {len(CORE_FIELDS)} 必填全在册）")
    return 1 if fail else 0


if __name__ == "__main__":
    sys.exit(main())
