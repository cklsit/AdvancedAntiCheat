#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AntiCheat 双版本兼容审计（Paper 1.21 编译 / Paper 1.8.8 运行）。

原理
----
本地 Maven 仓库里有真实的 spigot-api 1.8.8 jar，因此可以直接读它的 class 字节码，
确定性地回答"这个类型在 1.8 是 interface 还是 class""这个方法名/材质常量在 1.8 存不存在"，
不依赖任何人工维护的白名单。

检查项
------
  1) invokeinterface 的目标类型在 1.8.8 不是 interface  → [BLOCK]
     （100% 抛 IncompatibleClassChangeError，历史上表现为"点击没反应 + 物品能拖出"）
  2) 调用的 org/bukkit 方法名在 1.8.8 全库都不存在      → [BLOCK*]
     （编译能过、1.8 运行时抛 NoSuchMethodError，必须包在 try/catch(Throwable) 内）
  3) 引用的类型在 1.8.8 不存在                        → [REVIEW]
     （NoClassDefFoundError，须确认只在版本分支或 try/catch 内使用）
  4) 源码里 Material.<CONST> 在 1.8.8 枚举中不存在     → [BLOCK*]
     （编译能过、1.8 上 NoSuchFieldError）
  5) 监听器类里出现 1.8 不存在的事件类型               → [BLOCK]
     （Bukkit 的 registerEvents 以「类」为单位整体解析 @EventHandler，
      只要类里有 1.8 不存在的事件类型，该类的全部处理器都会被丢弃并打印
      "has failed to register events for class ..." —— 表现为整个检测模块静默失效。
      修法：把该事件搬到独立的监听器类，并在注册前用 Class.forName 探测；
      若确认已条件注册，在类上标注 // dual-version-guard: safe-conditional）

退出码
------
  0 = 无致命项（[BLOCK]）；2 = 存在致命项；1 = 工具自身无法运行（缺 jar / 缺 javap）

用法
----
  python3 tools/audit_dual_version.py                       # 自动找 target/ 下最新 jar
  python3 tools/audit_dual_version.py --jar path/to.jar
  python3 tools/audit_dual_version.py --api ~/.m2/.../spigot-api-1.8.8*.jar
  python3 tools/audit_dual_version.py --json report.json    # 额外输出机器可读报告
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

ACC_INTERFACE = 0x0200
CP_TAG_SIZES = {7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 15: 3, 3: 4, 4: 4, 9: 4, 10: 4,
                11: 4, 12: 4, 17: 4, 18: 4, 5: 8, 6: 8}
CLASS_DECL_RE = re.compile(
    r"^(?:public |final |abstract |public final |public abstract )*"
    r"(?:class|interface|enum)\s+([\w.$]+)")
CALL_RE = re.compile(r"(invokeinterface|invokevirtual|invokestatic)\s+\S+\s+//\s+"
                     r"(?:InterfaceMethod|Method)\s+([\w/$]+)\.([\w<>$]+):")
MATERIAL_RE = re.compile(r"Material\.([A-Z][A-Z0-9_]*)")

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


# --------------------------------------------------------------- 工具定位
def find_javap(explicit: str | None) -> str | None:
    if explicit:
        return explicit if os.path.exists(explicit) or shutil.which(explicit) else None
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        cand = os.path.join(java_home, "bin", "javap" + (".exe" if os.name == "nt" else ""))
        if os.path.exists(cand):
            return cand
    found = shutil.which("javap")
    if found:
        return found
    # Windows 上 javap 可能带 .exe 后缀而 which 找不到
    found = shutil.which("javap.exe")
    return found


def m2_root() -> str:
    for cand in (os.environ.get("MAVEN_REPO_LOCAL"),
                 os.path.join(os.path.expanduser("~"), ".m2", "repository")):
        if cand and os.path.isdir(cand):
            return cand
    return os.path.join(os.path.expanduser("~"), ".m2", "repository")


def find_api_jar(explicit: str | None) -> str | None:
    """优先用显式路径，否则在本地 Maven 仓库里找 1.8.8 的 spigot-api / bukkit。"""
    if explicit:
        return explicit if os.path.isfile(explicit) else None
    root = m2_root()
    patterns = [
        os.path.join(root, "org", "spigotmc", "spigot-api", "1.8.8-R0.1-SNAPSHOT", "spigot-api-1.8.8*.jar"),
        os.path.join(root, "org", "bukkit", "bukkit", "1.8.8-R0.1-SNAPSHOT", "bukkit-1.8.8*.jar"),
    ]
    for pattern in patterns:
        hits = [p for p in glob.glob(pattern)
                if not p.endswith("-sources.jar") and not p.endswith("-javadoc.jar")]
        if hits:
            # 固定版本号 jar 优先于带时间戳的 SNAPSHOT jar
            hits.sort(key=lambda p: ("2016" in os.path.basename(p), len(os.path.basename(p))))
            return hits[0]
    return None


def newest_jar(target: str) -> str | None:
    if not os.path.isdir(target):
        return None
    cands = [p for p in glob.glob(os.path.join(target, "*.jar"))
             if not re.search(r"(shaded|sources|javadoc|original)", os.path.basename(p))]
    return max(cands, key=os.path.getmtime) if cands else None


# --------------------------------------------------------------- 1.8.8 API 索引
def parse_class_file(data: bytes):
    """返回 (access_flags, [utf8 常量], 父类全限定名或 None)。"""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return None
    cp_count = int.from_bytes(data[8:10], "big")
    idx = 10
    utf8 = []
    cp = [None] * cp_count
    i = 1
    while i < cp_count:
        tag = data[idx]
        idx += 1
        if tag == 1:
            ln = int.from_bytes(data[idx:idx + 2], "big")
            idx += 2
            try:
                s = data[idx:idx + ln].decode("utf-8", errors="replace")
            except Exception:
                s = ""
            utf8.append(s)
            cp[i] = (1, s)
            idx += ln
        elif tag in CP_TAG_SIZES:
            size = CP_TAG_SIZES[tag]
            cp[i] = (tag, data[idx:idx + size])
            idx += size
            if tag in (5, 6):
                i += 1
        else:
            raise ValueError("未知常量池 tag=%d" % tag)
        i += 1
    flags = int.from_bytes(data[idx:idx + 2], "big")

    def class_name(cp_index):
        if cp_index == 0 or cp_index >= len(cp) or cp[cp_index] is None:
            return None
        _tag, payload = cp[cp_index]
        if _tag != 7:
            return None
        name_idx = int.from_bytes(payload[:2], "big")
        if name_idx >= len(cp) or cp[name_idx] is None:
            return None
        return cp[name_idx][1].replace("/", ".")

    super_name = None
    if idx + 6 <= len(data):
        super_name = class_name(int.from_bytes(data[idx + 4:idx + 6], "big"))
    return flags, utf8, super_name


class ApiIndex:
    """1.8.8 API 的常量池索引（interface 判定 / 方法名全集 / 超级类）。"""

    def __init__(self, jar: str):
        self.jar = jar
        self.is_interface: dict[str, bool] = {}
        self.super_of: dict[str, str | None] = {}
        self.names: set[str] = set()
        self.all_classes: set[str] = set()
        with zipfile.ZipFile(jar) as zf:
            for entry in zf.namelist():
                if not entry.endswith(".class"):
                    continue
                fqn = entry[:-6].replace("/", ".")
                self.all_classes.add(fqn)
                if not entry.startswith("org/bukkit/"):
                    continue
                try:
                    flags, utf8, super_name = parse_class_file(zf.read(entry))
                except Exception:
                    continue
                self.is_interface[fqn] = bool(flags & ACC_INTERFACE)
                self.super_of[fqn] = super_name
                self.names.update(utf8)

    def material_constants(self) -> set[str]:
        return {n for n in self.names if re.fullmatch(r"[A-Z][A-Z0-9_]*", n)}


# --------------------------------------------------------------- JDK 祖先方法
_JAVAP = "javap"
_JDK_INFO_CACHE: dict[str, tuple[set[str], str | None]] = {}


def jdk_class_info(cls: str):
    """javap 一个 JDK 类，返回 (声明的方法名集合, 父类名)。"""
    if cls in _JDK_INFO_CACHE:
        return _JDK_INFO_CACHE[cls]
    names, parent = set(), None
    try:
        res = subprocess.run([_JAVAP, "-p", cls], capture_output=True, timeout=60)
        for line in res.stdout.decode("utf-8", errors="replace").splitlines():
            s = line.strip()
            if parent is None:
                m = re.search(r"\bextends\s+([\w.$]+)", s)
                if m:
                    parent = m.group(1)
            if "(" in s and s.endswith(";"):
                mm = re.search(r"([\w$]+)\s*\(", s)
                if mm:
                    names.add(mm.group(1))
    except Exception:
        pass
    _JDK_INFO_CACHE[cls] = (names, parent)
    return names, parent


def declared_in_jdk_ancestor(cls: str, name: str, max_depth: int = 8) -> bool:
    seen, cur, depth = set(), cls, max_depth
    while cur and cur not in seen and depth > 0:
        seen.add(cur)
        names, parent = jdk_class_info(cur)
        if name in names:
            return True
        cur = parent
        depth -= 1
    return False


def declared_inherited(api: ApiIndex, owner: str, name: str) -> bool:
    """方法名不在 1.8 API 常量池里时，沿 1.8 类型的父类链到 JDK 侧确认一次。"""
    seen, cur, depth = set(), owner, 12
    while cur and cur not in seen and depth > 0:
        seen.add(cur)
        parent = api.super_of.get(cur)
        if parent is None:
            return False
        if parent not in api.is_interface:
            return declared_in_jdk_ancestor(parent, name)
        cur = parent
        depth -= 1
    return False


# --------------------------------------------------------------- 审计
def jar_classes(jar: str, prefix: str = "com/anticheat/") -> list[str]:
    """只审计本插件自己的类；com/anticheat/libs/ 是 shade 进包的三方库，跳过。"""
    with zipfile.ZipFile(jar) as zf:
        return [n[:-6].replace("/", ".") for n in zf.namelist()
                if n.endswith(".class") and n.startswith(prefix)
                and not n.startswith(prefix + "libs/")]


def javap_dump(jar: str, classes: list[str], batch: int = 40, timeout: int = 300) -> str:
    chunks = []
    for i in range(0, len(classes), batch):
        try:
            res = subprocess.run([_JAVAP, "-p", "-c", "-classpath", jar] + classes[i:i + batch],
                                 capture_output=True, timeout=timeout)
            chunks.append(res.stdout.decode("utf-8", errors="replace"))
        except Exception as e:  # pragma: no cover
            print("[WARN] javap 失败 (%d): %s" % (i, e))
    return "\n".join(chunks)


def audit(jar: str, api: ApiIndex):
    classes = jar_classes(jar)
    print("待审计类数: %d（1.8.8 API: %s）" % (len(classes), os.path.basename(api.jar)))
    dump = javap_dump(jar, classes)

    blocks, missing_types, missing_methods, inherited_ok = [], set(), set(), set()
    current = "?"
    for line in dump.splitlines():
        stripped = line.strip()
        m = CLASS_DECL_RE.match(stripped)
        if m and "{" in line:
            current = m.group(1)
            continue
        m = CALL_RE.search(line)
        if not m:
            continue
        owner, name = m.group(2).replace("/", "."), m.group(3)
        if not owner.startswith("org.bukkit"):
            continue
        is_iface = api.is_interface.get(owner)
        if is_iface is None:
            missing_types.add((owner, current))
            continue
        if m.group(1) == "invokeinterface" and not is_iface:
            blocks.append("%s 调 %s.%s —— 该类型在 1.8 是 class，不是 interface" % (current, owner, name))
        if name not in api.names:
            if declared_inherited(api, owner, name):
                inherited_ok.add((owner, name, current))
            else:
                missing_methods.add((owner, name, current))
    return blocks, missing_types, missing_methods, inherited_ok


def audit_materials(src_root: str, api_consts: set[str]):
    hits = []
    for root, _dirs, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            with open(path, encoding="utf-8", errors="replace") as fh:
                for i, line in enumerate(fh, 1):
                    code = line.strip()
                    if code.startswith("*") or code.startswith("//") or code.startswith("/*"):
                        continue
                    without_comment = line.split("//")[0]
                    for name in MATERIAL_RE.findall(without_comment):
                        if name not in api_consts:
                            hits.append((os.path.relpath(path, src_root), i, name, line.strip()))
    return hits


# --------------------------------------------------------------- 监听器类事件审计
CLASS_ANY_DECL_RE = re.compile(r"\b(?:class|interface|enum)\s+([A-Za-z_]\w*)")
EH_RE = re.compile(r"@EventHandler\b[^;{]*?public\s+void\s+\w+\s*\(\s*"
                   r"(?:final\s+)?([A-Za-z_][\w.]*)\s+")
IMPORT_RE = re.compile(r"^[ \t]*import[ \t]+(?:static[ \t]+)?([A-Za-z_][\w.]*)[ \t]*;", re.M)
SAFE_CONDITIONAL_MARK = "dual-version-guard: safe-conditional"


def audit_listener_events(src_root: str, api: ApiIndex, plugin_classes=None):
    """检查监听器类里是否出现 1.8 不存在的事件类型（会导致整类处理器被丢弃）。

    注意：源码里通常写的是**简单类名**（PlayerSwapHandItemsEvent），
    必须借 import 解析成 FQN 才能和 1.8 API 比对。若只认源码中带 "org.bukkit."
    前缀的写法，本检查会永久空转——比没有检查更危险（会有虚假的安全感）。

    返回 (problems, safe, unresolved)。
    """
    plugin_simple = {c.rsplit(".", 1)[-1] for c in (plugin_classes or [])}
    api_simple: dict[str, str] = {}
    for fqn in api.all_classes:
        api_simple.setdefault(fqn.rsplit(".", 1)[-1], fqn)

    problems, safe, unresolved = [], [], []
    for root, _dirs, files in os.walk(src_root):
        for f in files:
            if not f.endswith(".java"):
                continue
            path = os.path.join(root, f)
            rel = os.path.relpath(path, src_root)
            with open(path, encoding="utf-8", errors="replace") as fh:
                raw = fh.read()
            stripped = strip_java_comments(raw)
            raw_lines = raw.splitlines()

            imports: dict[str, str] = {}
            for im in IMPORT_RE.finditer(stripped):
                fq = im.group(1)
                imports[fq.rsplit(".", 1)[-1]] = fq

            # 类声明位置 → 每个类的源码片段（stripped 与 raw 行号一致）
            decls = [(m.start(), m.group(1)) for m in CLASS_ANY_DECL_RE.finditer(stripped)]
            if not decls:
                continue
            for m in EH_RE.finditer(stripped):
                declared = m.group(1)
                # 解析成 FQN：带点直接信；否则查 import
                fqn = declared if "." in declared else imports.get(declared)
                if fqn is None:
                    # 没 import：要么是 1.8 里存在的同名类，要么是插件自定义事件，要么是真异常
                    if declared in api_simple or declared in plugin_simple:
                        continue
                    unresolved.append((rel, declared))
                    continue
                if not fqn.startswith("org.bukkit."):
                    continue  # 插件或三方自定义事件，随插件一起加载，1.8 不受影响
                event_type = fqn
                if event_type in api.all_classes:
                    continue
                # 找最近的（在其之前的）类声明作为宿主类
                owner, span_start, span_end = None, 0, len(stripped)
                for idx, (pos, name) in enumerate(decls):
                    if pos < m.start():
                        owner = name
                        span_start = pos
                        span_end = decls[idx + 1][0] if idx + 1 < len(decls) else len(stripped)
                    else:
                        break
                if owner is None:
                    owner = os.path.basename(path).replace(".java", "")
                # 宿主类必须真的是 Listener
                head = stripped[span_start:min(span_start + 400, span_end)]
                if "Listener" not in head:
                    continue
                # 标记必须写在「原始源码」里（它是注释，已被 strip 掉）
                start_line = stripped.count("\n", 0, span_start)
                end_line = stripped.count("\n", 0, span_end)
                span_raw = "\n".join(raw_lines[start_line:end_line])
                item = (rel, owner, event_type)
                if SAFE_CONDITIONAL_MARK in span_raw:
                    safe.append(item)
                else:
                    problems.append(item)
    return problems, safe, unresolved


def strip_java_comments(src: str) -> str:
    """去掉注释，但保留换行符，保证行号与原文件一致（便于回查原始注释标记）。"""
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                i += 1
        elif c == "/" and nxt == "*":
            i += 2
            while i < n - 1 and not (src[i] == "*" and src[i + 1] == "/"):
                if src[i] == "\n":
                    out.append("\n")
                i += 1
            i += 2
        elif c == '"':
            out.append(c)
            i += 1
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    i += 1
                i += 1
            if i < n:
                out.append('"')
                i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def newest_source_mtime(src: str) -> float:
    """源码（含 resources / plugin.yml）的最新修改时间。"""
    newest = 0.0
    roots = [src, os.path.join(REPO_ROOT, "src", "main", "resources")]
    for root in roots:
        for dirpath, _dirs, files in os.walk(root):
            for f in files:
                if f.endswith((".java", ".yml", ".json")):
                    newest = max(newest, os.path.getmtime(os.path.join(dirpath, f)))
    extra = os.path.join(REPO_ROOT, "plugin.yml")
    if os.path.exists(extra):
        newest = max(newest, os.path.getmtime(extra))
    return newest


def main() -> int:
    global _JAVAP
    ap = argparse.ArgumentParser(description="AntiCheat 双版本兼容审计")
    ap.add_argument("--target", default=os.path.join(REPO_ROOT, "target"))
    ap.add_argument("--jar", default=None, help="显式指定插件 jar（默认取 target 下最新）")
    ap.add_argument("--src", default=os.path.join(REPO_ROOT, "src", "main", "java"))
    ap.add_argument("--api", default=None, help="显式指定 spigot-api 1.8.8 jar")
    ap.add_argument("--javap", default=None, help="显式指定 javap 可执行文件")
    ap.add_argument("--json", default=None, help="把结果写成 JSON 报告")
    args = ap.parse_args()

    javap = find_javap(args.javap)
    if not javap:
        print("[ERROR] 找不到 javap，请设置 JAVA_HOME 或用 --javap 指定")
        return 1
    _JAVAP = javap

    jar = args.jar or newest_jar(args.target)
    if not jar:
        print("[ERROR] target 下找不到主 jar，请先编译")
        return 1
    api_jar = find_api_jar(args.api)
    if not api_jar:
        print("[ERROR] 找不到 spigot-api 1.8.8 jar，无法做确定性审计")
        print("        CI 里可先执行： mvn -q dependency:get -Dartifact=org.spigotmc:spigot-api:1.8.8-R0.1-SNAPSHOT")
        return 1
    api = ApiIndex(api_jar)

    print("审计 jar : %s (%.1f MB)" % (jar, os.path.getsize(jar) / 1048576.0))

    # 陈旧产物检测：审计一个比源码旧的 jar 得出的「全绿」毫无意义，且极易误判
    # （真实踩过：jar 停在 21:11、源码改到 21:56，审计照样报 [OK]，但真机跑的是旧代码）。
    jar_mtime = os.path.getmtime(jar)
    src_mtime = newest_source_mtime(args.src)
    if src_mtime > jar_mtime + 1.0:
        print("\n[BLOCK] 产物陈旧：源码(%s) 比 jar(%s) 更新，审计结果不代表当前代码"
              % (time.strftime("%m-%d %H:%M", time.localtime(src_mtime)),
                 time.strftime("%m-%d %H:%M", time.localtime(jar_mtime))))
        print("   请先重新打包： mvn -q package -DskipTests -DskipFrontend=true -Ppaper")
        if args.json:
            with open(args.json, "w", encoding="utf-8") as fh:
                json.dump({"jar": os.path.basename(jar), "staleJar": True, "fatal": True},
                          fh, ensure_ascii=False, indent=2)
        return 2

    blocks, missing_types, missing_methods, inherited_ok = audit(jar, api)

    print("\n--- 1) invokeinterface 目标在 1.8 是否为 interface ---")
    if blocks:
        for b in sorted(set(blocks)):
            print("[BLOCK] %s" % b)
    else:
        print("[OK] 无（1.8 上是 class 的 Bukkit 类型没有被 invokeinterface 调用）")

    print("\n--- 2) 调用了 1.8 全库不存在的方法名（须 try/catch）---")
    if missing_methods:
        for owner, name, cls in sorted({(o, n, c) for o, n, c in missing_methods})[:40]:
            print("[BLOCK*] %s -> %s.%s" % (cls, owner, name))
        print("   说明：带 * 表示编译能过、1.8 运行时抛 NoSuchMethodError，"
              "确认已包在 try/catch(Throwable) 内即可放行")
    else:
        print("[OK] 无")
    if inherited_ok:
        uniq = sorted({(o, n) for o, n, _c in inherited_ok})
        print("   （已排除 %d 个继承自 JDK 父类的方法，如 %s——1.8 运行时正常）"
              % (len(uniq), ", ".join("%s.%s" % (o.split(".")[-1], n) for o, n in uniq[:6])))

    print("\n--- 3) 引用了 1.8 不存在的类型 ---")
    if missing_types:
        for owner, cls in sorted({(o, c) for o, c in missing_types})[:30]:
            print("[REVIEW] %s -> %s（确认只在现代分支或 try/catch 内出现）" % (cls, owner))
    else:
        print("[OK] 无")

    print("\n--- 4) 源码 Material 常量是否存在于 1.8 枚举 ---")
    mat = audit_materials(args.src, api.material_constants())
    if mat:
        for rel, i, name, line in mat:
            print("[BLOCK*] %s:%d  Material.%s  (1.8 无此常量)" % (rel, i, name))
            print("          %s" % line)
        print("   修复：改用 VersionUtil.compatMaterial(现代名, 1.8名, fallback)")
    else:
        print("[OK] 无")

    print("\n--- 5) 监听器类的 @EventHandler 事件类型是否存在于 1.8 ---")
    listener_problems, listener_safe, listener_unresolved = audit_listener_events(
        args.src, api, jar_classes(jar))
    if listener_problems:
        for rel, owner, event_type in sorted(set(listener_problems)):
            print("[BLOCK] %s 的监听器类 %s 处理 %s —— 1.8 无此事件" % (rel, owner, event_type))
        print("   后果：Bukkit 会「整类」丢弃该监听器，该类里其余 1.8 可用的检测会一起静默失效。")
        print("   修复：把该事件移到独立监听器类，注册前用 Class.forName 探测事件是否存在；")
        print("        确认已条件注册的，在类上标注 // %s" % SAFE_CONDITIONAL_MARK)
    else:
        print("[OK] 无")
    for rel, owner, event_type in sorted(set(listener_safe)):
        print("   [NOTE] %s 的 %s 处理 %s（已标注条件注册，豁免）" % (rel, owner, event_type))
    for rel, declared in sorted(set(listener_unresolved)):
        print("   [REVIEW] %s 的 @EventHandler 类型 %s 无法解析（无 import，且 1.8 与插件类里都无同名类）"
              % (rel, declared))

    fatal = bool(blocks) or bool(listener_problems)
    print("\n结论：%s" % ("存在致命项，禁止部署" if fatal else
                        "无致命项；BLOCK*/REVIEW 项请确认已在 try/catch 或版本分支内"))

    if args.json:
        report = {
            "jar": os.path.basename(jar),
            "apiJar": os.path.basename(api.jar),
            "auditedClasses": len(jar_classes(jar)),
            "invokeInterfaceBlocks": sorted(set(blocks)),
            "missingMethods": sorted({"%s -> %s.%s" % (c, o, n) for o, n, c in missing_methods}),
            "missingTypes": sorted({"%s -> %s" % (c, o) for o, c in missing_types}),
            "legacyInheritedOk": len({(o, n) for o, n, _ in inherited_ok}),
            "materialViolations": [
                {"file": rel, "line": i, "constant": name, "source": line}
                for rel, i, name, line in mat
            ],
            "listenerEventViolations": [
                {"file": rel, "listenerClass": owner, "event": event_type}
                for rel, owner, event_type in sorted(set(listener_problems))
            ],
            "listenerConditionalExempt": [
                {"file": rel, "listenerClass": owner, "event": event_type}
                for rel, owner, event_type in sorted(set(listener_safe))
            ],
            "listenerUnresolved": [
                {"file": rel, "declaredType": declared}
                for rel, declared in sorted(set(listener_unresolved))
            ],
            "fatal": fatal,
        }
        parent = os.path.dirname(os.path.abspath(args.json))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(args.json, "w", encoding="utf-8") as fh:
            json.dump(report, fh, ensure_ascii=False, indent=2)
        print("JSON 报告: %s" % args.json)

    return 2 if fatal else 0


if __name__ == "__main__":
    sys.exit(main())
