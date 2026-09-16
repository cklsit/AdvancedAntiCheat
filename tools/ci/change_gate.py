#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""变更 → 功能 门禁：本次提交动了哪些功能面，这些功能面是否真的被测试覆盖。

为什么需要它：
  只跑全量测试无法回答「新提交的功能是否正常」——新加的接口/命令/模块如果没有对应
  断言，全绿也说明不了任何问题。本脚本把 diff 映射到功能面，再要求：
    1. 该功能面声明的 JUnit 测试类必须存在，且本次 **实际跑过并通过**（读 surefire 结果）；
    2. 本次 diff 新出现的 Web 路由（无路径参数的那些）必须被 E2E 断言探针覆盖；
    3. 本次 diff 新出现的控制台命令必须被 E2E 命令断言覆盖；
    4. 该功能面在 feature_map.json 里声明的接口清单必须全部被 E2E 覆盖。
  任何一条不满足即失败（gate=block 的规则），迫使「加功能」与「加断言」同步发生。

用法：
  python tools/ci/change_gate.py --base origin/main --head HEAD \
      --surefire target/surefire-reports \
      --e2e .ci-e2e/report-1.21.11.json --e2e .ci-e2e/report-1.8.8.json \
      --report change-gate.json

  # 或直接指定文件（本地调试）
  python tools/ci/change_gate.py --files src/main/java/com/anticheat/ai/AiMath.java

退出码：0 = 通过（可有 warn）；1 = 存在阻断项。
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
MAP_FILE = REPO / "tools" / "ci" / "feature_map.json"
E2E_SCRIPT = REPO / "tools" / "ci" / "server_e2e.py"

ROUTE_ADD_RE = re.compile(r'app\.(?:get|post|put|delete|patch|ws)\("(/[^"]+)"')
CMD_ADD_RE = re.compile(r"^\s{2}([a-z][a-z0-9_-]{1,30}):\s*$")
E2E_ROUTE_RE = re.compile(r'"(/api/[^"]*)"')
E2E_CMD_RE = re.compile(r'"(/(?:ac|anticheat)[^"]*)"')
PLACEHOLDER_RE = re.compile(r"\{[^}]*\}")


# --------------------------------------------------------------------------- 基础

def log(msg: str = "") -> None:
    print(msg, flush=True)


def run_git(args: list[str]) -> str:
    res = subprocess.run(["git"] + args, cwd=str(REPO), capture_output=True, text=True, errors="replace")
    if res.returncode != 0:
        raise RuntimeError("git %s 失败: %s" % (" ".join(args), res.stderr.strip()))
    return res.stdout


def glob_match(path: str, pattern: str) -> bool:
    """支持 ** 的极简 glob（够用即可，避免引入依赖）。"""
    if pattern.endswith("/**"):
        return path.startswith(pattern[:-3] + "/") or path == pattern[:-3]
    if "**" in pattern:
        head, _, tail = pattern.partition("**")
        return path.startswith(head) and path.endswith(tail.lstrip("/"))
    if pattern.startswith("**/"):
        return path.endswith(pattern[3:])
    return path == pattern


def load_map() -> dict:
    with open(MAP_FILE, encoding="utf-8") as fh:
        return json.load(fh)


def match_rule(path: str, rules: list[dict]) -> dict | None:
    for rule in rules:
        for pattern in rule["paths"]:
            if glob_match(path, pattern):
                return rule
    return None


def diff_of(base: str, head: str) -> str:
    return run_git(["diff", "--unified=0", "--no-color", "%s...%s" % (base, head)])


def parse_diff(diff: str) -> dict[str, list[str]]:
    """返回 {文件路径: [新增行内容, ...]}。"""
    added: dict[str, list[str]] = {}
    current = None
    for line in diff.splitlines():
        if line.startswith("+++ "):
            target = line[4:].strip()
            current = None if target == "/dev/null" else target[2:] if target.startswith("b/") else target
            if current:
                added.setdefault(current, [])
        elif line.startswith("+") and not line.startswith("+++"):
            if current:
                added[current].append(line[1:])
    return added


def surefire_results(dirpath: Path) -> dict[str, dict]:
    """返回 {测试类全名: {tests, failures, errors, skipped}}。"""
    out: dict[str, dict] = {}
    if not dirpath.exists():
        return out
    for xml in dirpath.glob("TEST-*.xml"):
        try:
            root = ET.parse(xml).getroot()
        except ET.ParseError:
            continue
        name = root.get("name") or xml.stem.replace("TEST-", "")
        out[name] = {
            "tests": int(root.get("tests") or 0),
            "failures": int(root.get("failures") or 0),
            "errors": int(root.get("errors") or 0),
            "skipped": int(root.get("skipped") or 0),
        }
    if out:
        return out
    # 退化到 .txt 汇总
    for txt in dirpath.glob("*.txt"):
        text = txt.read_text(encoding="utf-8", errors="replace")
        m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", text)
        if m:
            out[txt.stem] = {"tests": int(m.group(1)), "failures": int(m.group(2)),
                             "errors": int(m.group(3)), "skipped": int(m.group(4))}
    return out


def e2e_probed(reports: list[Path]) -> set[str]:
    """E2E 已断言过的接口与命令集合（报告 + 生成脚本声明，双来源合并）。"""
    probed: set[str] = set()
    text = E2E_SCRIPT.read_text(encoding="utf-8", errors="replace") if E2E_SCRIPT.exists() else ""
    for m in E2E_ROUTE_RE.finditer(text):
        probed.add(m.group(1))
    for m in E2E_CMD_RE.finditer(text):
        probed.add(m.group(1))
    for report in reports:
        if not report or not Path(report).exists():
            continue
        data = json.loads(Path(report).read_text(encoding="utf-8", errors="replace"))
        for check in data.get("checks", []):
            name = check.get("name", "")
            for m in re.finditer(r"(/api/[A-Za-z0-9_/{}\-]+)", name):
                probed.add(m.group(1))
            if name.startswith("/ac"):
                probed.add(name)
    return probed


# --------------------------------------------------------------------------- 主流程

def parse_args(argv=None):
    p = argparse.ArgumentParser(description="变更→功能映射门禁")
    p.add_argument("--base", default="", help="对比基线 ref（如 origin/main 或上一个提交）")
    p.add_argument("--head", default="HEAD")
    p.add_argument("--files", default="", help="直接指定改动文件（逗号分隔），与 --base 二选一")
    p.add_argument("--diff-file", default="", help="直接读一个 unified diff 文件（本地复现/调试用）")
    p.add_argument("--surefire", default="target/surefire-reports")
    p.add_argument("--e2e", action="append", default=[], help="E2E 报告 JSON（可多次）")
    p.add_argument("--report", default="", help="门禁报告输出路径")
    p.add_argument("--no-enforce", action="store_true", help="只出报告不阻断（本地排查用）")
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    spec = load_map()
    rules = spec["rules"]

    if args.files:
        changed = {f.strip(): [] for f in args.files.split(",") if f.strip()}
        diff_map = dict(changed)
    elif args.diff_file:
        diff_map = parse_diff(Path(args.diff_file).read_text(encoding="utf-8", errors="replace"))
    else:
        if not args.base:
            log("必须提供 --base 或 --files")
            return 1
        diff = diff_of(args.base, args.head)
        diff_map = parse_diff(diff)
        if not diff_map:
            log("本次提交没有文件改动，门禁通过。")
            if args.report:
                Path(args.report).write_text(json.dumps(
                    {"features": [], "violations": [], "warnings": [], "passed": True},
                    ensure_ascii=False, indent=2), encoding="utf-8")
            return 0

    results = surefire_results(Path(args.surefire))
    probed = e2e_probed([Path(p) for p in args.e2e])
    e2e_available = bool(probed)

    # ---- 1) 文件 → 功能面
    feature_hits: dict[str, dict] = {}
    unmapped: list[str] = []
    for path in sorted(diff_map):
        if path.startswith("src/test/"):
            continue
        rule = match_rule(path, rules)
        if rule is None:
            unmapped.append(path)
            continue
        entry = feature_hits.setdefault(rule["id"], {"rule": rule, "files": []})
        entry["files"].append(path)

    violations: list[str] = []
    warnings: list[str] = []

    for fid, entry in sorted(feature_hits.items()):
        rule = entry["rule"]
        gate = rule.get("gate", "block")
        log("")
        log("▌ 功能面: %s  (%s)" % (rule["feature"], fid))
        log("  规则档位: %s" % gate)
        for f in entry["files"]:
            log("    · 改动文件 %s" % f)

        # ---- 2) 必须存在的 JUnit 测试，且本次真实跑过并通过
        for fqn in rule.get("tests", []):
            test_file = REPO / "src" / "test" / "java" / (fqn.replace(".", "/") + ".java")
            if not test_file.exists():
                msg = "[%s] 声明了测试 %s，但文件不存在（%s）" % (fid, fqn, test_file.relative_to(REPO))
                violations.append(msg) if gate == "block" else warnings.append(msg)
                log("    ✗ 测试缺失: %s" % fqn)
                continue
            res = results.get(fqn) or results.get(fqn.split(".")[-1])
            if res is None:
                msg = "[%s] 测试 %s 本次未运行（surefire 无结果）；测试步骤需在门禁之前完成" % (fid, fqn)
                violations.append(msg) if gate == "block" else warnings.append(msg)
                log("    ✗ 未运行: %s" % fqn)
            elif res["failures"] or res["errors"]:
                msg = "[%s] 测试 %s 未通过：failures=%d errors=%d" % (fid, fqn, res["failures"], res["errors"])
                violations.append(msg)
                log("    ✗ 未通过: %s" % fqn)
            elif res["tests"] == 0:
                msg = "[%s] 测试 %s 一个用例都没跑（空测试类等于没覆盖）" % (fid, fqn)
                violations.append(msg) if gate == "block" else warnings.append(msg)
                log("    ✗ 零用例: %s" % fqn)
            else:
                log("    ✓ 测试通过: %s (%d 用例)" % (fqn, res["tests"]))

        # ---- 3) 功能面声明的接口必须被 E2E 覆盖
        declared = list(rule.get("endpoints", [])) + list(rule.get("commands", []))
        if declared and gate == "block" and not e2e_available:
            violations.append("[%s] 该功能面要求 E2E 覆盖 %d 个接口/命令，但未提供 E2E 报告" % (fid, len(declared)))
        for item in declared:
            if e2e_available and item not in probed:
                msg = "[%s] 声明的探针 %s 未被任何 E2E 断言覆盖" % (fid, item)
                violations.append(msg) if gate == "block" else warnings.append(msg)
                log("    ✗ E2E 未覆盖: %s" % item)
            elif e2e_available:
                log("    ✓ E2E 已覆盖: %s" % item)
        if rule.get("notes"):
            log("    ℹ %s" % rule["notes"])

    # ---- 4) 新增路由 / 命令必须被 E2E 覆盖
    new_routes, new_commands = [], []
    for path, adds in diff_map.items():
        if path.endswith(".java") and path.startswith("src/main/java/"):
            for line in adds:
                for m in ROUTE_ADD_RE.finditer(line):
                    new_routes.append((path, m.group(1)))
        if path.endswith("plugin.yml"):
            for line in adds:
                m = CMD_ADD_RE.match(line)
                if m:
                    new_commands.append((path, m.group(1)))

    if new_routes or new_commands:
        log("")
        log("▌ 本次新增的路由 / 命令")
        for path, route in sorted(set(new_routes)):
            if PLACEHOLDER_RE.search(route):
                warnings.append("新增路由 %s 含路径参数，E2E 无法直接探测，请确认是否间接覆盖" % route)
                log("    ~ %s （含路径参数，跳过强制探针）" % route)
                continue
            if not e2e_available or route not in probed:
                violations.append("新增路由 %s 未被 E2E 断言覆盖（改 tools/ci/server_e2e.py 的 READ_ENDPOINTS 或加专项断言）" % route)
                log("    ✗ 未覆盖: %s" % route)
            else:
                log("    ✓ 已覆盖: %s" % route)
        for path, cmd in sorted(set(new_commands)):
            probe = "/" + cmd
            if not e2e_available or probe not in probed:
                violations.append("新增命令 /%s 未被 E2E 命令断言覆盖（改 tools/ci/server_e2e.py 的 CONSOLE_CHECKS）" % cmd)
                log("    ✗ 未覆盖: /%s" % cmd)
            else:
                log("    ✓ 已覆盖: /%s" % cmd)

    for path in unmapped:
        if not path.startswith(("src/main/java/", "src/main/resources/")):
            continue
        warnings.append("新增/改动了未被 feature_map.json 覆盖的路径：%s（请补规则，否则该文件的功能面无测试约束）" % path)

    # ---- 汇总
    log("")
    log("=" * 72)
    log("变更门禁汇总：%d 个功能面受影响，%d 项阻断，%d 项提醒"
        % (len(feature_hits), len(violations), len(warnings)))
    for v in violations:
        log("  [BLOCK] %s" % v)
    for w in warnings:
        log("  [WARN ] %s" % w)
    log("=" * 72)

    passed = args.no_enforce or not violations
    if args.report:
        out = Path(args.report)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({
            "features": [
                {"id": fid, "feature": e["rule"]["feature"], "files": e["files"]}
                for fid, e in sorted(feature_hits.items())
            ],
            "newRoutes": sorted({r for _, r in new_routes}),
            "newCommands": sorted({c for _, c in new_commands}),
            "violations": violations,
            "warnings": warnings,
            "e2eReports": [str(p) for p in args.e2e],
            "passed": passed,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        log("报告已写入: %s" % out)

    if args.no_enforce:
        log("（--no-enforce：仅报告，不阻断）")
        return 0
    return 0 if not violations else 1


if __name__ == "__main__":
    sys.exit(main())
