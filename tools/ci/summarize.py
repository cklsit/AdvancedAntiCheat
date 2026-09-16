#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 CI 各步骤的机器可读产物汇总成一份 Markdown 报告（写进 GitHub Step Summary）。

上游产物：
  * target/surefire-reports/TEST-*.xml   —— JUnit 单测 / 契约测试
  * .ci-e2e/report-<version>.json        —— 真机 E2E（server_e2e.py）
  * .ci-tools/audit.json                 —— 双版本兼容审计（audit_dual_version.py）
  * .ci-tools/change-gate.json           —— 变更→功能门禁（change_gate.py）

用法：
  python tools/ci/summarize.py --surefire target/surefire-reports \
      --e2e .ci-e2e/report-1.8.8.json --e2e .ci-e2e/report-1.21.11.json \
      --audit .ci-tools/audit.json --gate .ci-tools/change-gate.json \
      --out summary.md
"""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_ROWS = 40


def load_json(path: str):
    if not path:
        return None
    p = Path(path)
    if not p.is_file():
        return None
    try:
        return json.loads(p.read_text(encoding="utf-8", errors="replace"))
    except json.JSONDecodeError:
        return None


def surefire_rows(dirpath: Path) -> list[dict]:
    if not dirpath.exists():
        return []
    rows = []
    for xml in sorted(dirpath.glob("TEST-*.xml")):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        rows.append({
            "name": root.get("name") or xml.stem,
            "tests": int(root.get("tests") or 0),
            "failures": int(root.get("failures") or 0),
            "errors": int(root.get("errors") or 0),
            "skipped": int(root.get("skipped") or 0),
            "time": float(root.get("time") or 0),
        })
    return rows


def md_surefire(dirpath: Path) -> list[str]:
    rows = surefire_rows(dirpath)
    if not rows:
        return ["_未找到 surefire 结果_", ""]
    total = sum(r["tests"] for r in rows)
    bad = sum(r["failures"] + r["errors"] for r in rows)
    icon = "✅" if bad == 0 else "❌"
    out = ["%s **%d 个测试类 / %d 个用例**，失败 %d" % (icon, len(rows), total, bad), ""]
    out.append("| 测试类 | 用例 | 失败 | 错误 | 跳过 | 耗时(s) |")
    out.append("| --- | ---: | ---: | ---: | ---: | ---: |")
    for r in rows[:MAX_ROWS]:
        flag = "" if r["failures"] + r["errors"] == 0 else " ⚠️"
        out.append("| %s%s | %d | %d | %d | %d | %.2f |"
                   % (r["name"], flag, r["tests"], r["failures"], r["errors"], r["skipped"], r["time"]))
    if len(rows) > MAX_ROWS:
        out.append("| _…其余 %d 个测试类省略_ | | | | | |" % (len(rows) - MAX_ROWS))
    out.append("")
    return out


def md_e2e(reports: list[str]) -> list[str]:
    out = []
    any_missing = False
    for path in reports:
        data = load_json(path)
        if data is None:
            out.append("- ❓ `%s` 未生成（该版本的 E2E 未跑或未上传）" % path)
            any_missing = True
            continue
        bad = data.get("failed", 0)
        icon = "✅" if data.get("passed") else "❌"
        out.append("- %s **Paper %s**：%d/%d 通过，失败 %d，耗时 %ss"
                   % (icon, data.get("version"), data.get("total", 0) - bad,
                      data.get("total", 0), bad, data.get("durationSec")))
        for check in data.get("checks", []):
            if not check.get("ok"):
                detail = (check.get("detail") or "").strip().splitlines()
                brief = detail[0] if detail else ""
                out.append("    - ❌ `[%s] %s` %s" % (check.get("group"), check.get("name"), brief))
    if not out:
        out.append("_未提供 E2E 报告_")
    if any_missing:
        out.append("")
        out.append("> E2E 报告缺失说明该版本的真机测试没有产出结果——按「未验证」处理，不要当作通过。")
    out.append("")
    return out


def md_audit(path: str) -> list[str]:
    data = load_json(path)
    if data is None:
        return ["_未找到审计报告_", ""]
    out = []
    fatal = data.get("fatal")
    out.append("%s 审计类数 %s，致命项 %s"
               % ("✅" if not fatal else "❌", data.get("auditedClasses", "?"),
                  "有" if fatal else "无"))
    # 已标注条件注册的（audit 第 5 项豁免）不算问题，否则报告会长期带着一条假红
    exempt_events = {e.get("event") for e in (data.get("listenerConditionalExempt") or [])}
    blocks = (data.get("invokeInterfaceBlocks") or []) + (data.get("listenerEventViolations") or [])
    for t in (data.get("missingTypes") or []):
        if any(ev and ev in str(t) for ev in exempt_events):
            continue
        blocks.append(t)
    if data.get("materialViolations"):
        blocks = list(blocks) + ["Material: %s:%s %s" % (m.get("file"), m.get("line"), m.get("constant"))
                                 for m in data["materialViolations"]]
    if exempt_events:
        out.append("")
        out.append("- 已条件注册豁免：%s" % "、".join(sorted(e for e in exempt_events if e)))
    if blocks:
        out.append("")
        out.append("| 疑似跨版本问题 |")
        out.append("| --- |")
        for b in blocks[:20]:
            out.append("| %s |" % (b if isinstance(b, str) else json.dumps(b, ensure_ascii=False)))
    out.append("")
    return out


def md_gate(path: str) -> list[str]:
    data = load_json(path)
    if data is None:
        return ["_未找到变更门禁报告_", ""]
    out = []
    features = data.get("features") or []
    out.append("%s 本次变更影响 **%d 个功能面**，阻断 %d 项，提醒 %d 项"
               % ("✅" if data.get("passed") else "❌", len(features),
                  len(data.get("violations") or []), len(data.get("warnings") or [])))
    if features:
        out.append("")
        out.append("| 功能面 | 改动文件 |")
        out.append("| --- | --- |")
        for f in features:
            out.append("| %s | %s |" % (f.get("feature"), "<br>".join(f.get("files") or [])))
    for v in data.get("violations") or []:
        out.append("")
        out.append("> ❌ **阻断**：%s" % v)
    for w in data.get("warnings") or []:
        out.append("")
        out.append("> ⚠️ 提醒：%s" % w)
    out.append("")
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="CI 报告汇总")
    ap.add_argument("--surefire", default="target/surefire-reports")
    ap.add_argument("--e2e", action="append", default=[])
    ap.add_argument("--audit", default="")
    ap.add_argument("--gate", default="")
    ap.add_argument("--out", default="")
    args = ap.parse_args(argv)

    lines = ["# AntiCheat CI 报告", ""]
    lines += ["## 1. 单元 / 契约测试", ""] + md_surefire(Path(args.surefire))
    lines += ["## 2. 真机端到端（Paper 1.8.8 / 1.21.11）", ""] + md_e2e(args.e2e)
    lines += ["## 3. 双版本兼容审计（1.21 编译 · 1.8.8 运行）", ""] + md_audit(args.audit)
    lines += ["## 4. 变更 → 功能 门禁", ""] + md_gate(args.gate)

    text = "\n".join(lines).rstrip() + "\n"
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
        print("报告已写入: %s" % args.out)
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
