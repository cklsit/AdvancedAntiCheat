#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""真机端到端测试：在真实 MC 服务端上装载 AdvancedAntiCheat 并断言功能可用。

与单元测试的分工：
  * 单元测试 / 契约测试  —— 纯 JVM，验证算法正确性、配置键与命令契约是否自洽；
  * 本脚本            —— 真服务端 + 真插件，验证「装上以后功能到底能不能用」。

断言覆盖两层：
  1. 启动健康度 —— 插件启用、无「整类监听器注册失败」、无本插件栈帧异常；
  2. 控制台命令 —— /ac help|stats|reports|reload|profile 等逐一执行并核对输出。

Web 面板与观察者回放已整体下线，原先的 HTTP 端点断言（第 3、4 层）已随之移除。

用法：
  python tools/ci/server_e2e.py --version 1.21.11 \
      --plugin target/AdvancedAntiCheat-2.1.0.jar \
      --workdir .ci-e2e/1.21.11 --mc-port 25565 \
      --java "/c/Program Files/Zulu/zulu-21/bin/java.exe" --report .ci-e2e/1.21.11.json

退出码：0 = 全通过；1 = 有失败项；3 = 环境/启动期致命错误（无法进入断言阶段）。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

# --------------------------------------------------------------------------- 常量

PAPER_PINNED_BUILD = {"1.8.8": "445", "1.21.11": "132"}
# api.papermc.io/v2 已于 2026 年下线（410 Gone），统一走 fill.papermc.io/v3。
# 注意 v3 的 builds 列表是**新→旧**倒序，最新构建是 [0] 而不是 [-1]。
FILL_BUILDS_V3 = "https://fill.papermc.io/v3/projects/paper/versions/{v}/builds"
USER_AGENT = "AdvancedAntiCheat-CI/2.1 (+https://github.com/cklsit/AntiCheat)"

ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
SECTION_RE = re.compile(r"\u00a7[0-9a-fk-orA-FK-OR]")

# 启动期「硬伤」：出现即判失败
FATAL_LOG_PATTERNS = [
    ("插件 enable 阶段抛异常（插件会被整体禁用）",
     r"Error occurred while enabling|Could not load .*AdvancedAntiCheat|while enabling AdvancedAntiCheat"),
    ("监听器整类注册失败（1.8 上的静默失效元凶）", r"has failed to register events"),
    ("事件处理器抛异常", r"Could not pass event"),
    ("命令执行抛异常", r"Command exception|An unexpected error occurred trying to execute"),
    ("类加载失败", r"NoClassDefFoundError|ClassNotFoundException"),
    ("方法缺失（跨版本 API 不兼容）", r"NoSuchMethodError"),
    ("字段缺失（跨版本枚举不兼容）", r"NoSuchFieldError"),
    ("接口/类不一致（InventoryView 那类坑）", r"IncompatibleClassChangeError"),
    ("抽象方法未实现", r"AbstractMethodError"),
    ("字节码版本过高（Java 版本不匹配）", r"UnsupportedClassVersionError"),
    # 栈帧可能带 jar 前缀（Paper 上是 "at AdvancedAntiCheat-2.1.0.jar//com.anticheat.X"），
    # 早期只匹配 "^  at com.anticheat." 会漏掉这种最常见的形态
    ("插件自身栈帧异常", r"^\s+at\s+(?:[\w.\-]+\.jar//)?com\.anticheat\."),
]

STARTUP_REQUIRED = [
    ("插件已启用", r"插件已成功启用"),
    ("版本识别正常", r"检测到服务器版本"),
]

# 异步启动的子系统：要在「Done」之后继续等，不能立刻判定。
# Web 面板与观察者回放已整体下线，当前没有需要延后等待的子系统；保留空表以便后续新增。
STARTUP_DEFERRED: list[tuple[str, str]] = []

STARTUP_FORBIDDEN = [
    ("AI 实验室初始化失败（退化为纯规则模式）", r"AI 实验室初始化失败"),
]

# 控制台命令断言：任一 marker 命中即通过（soft=True 的命令只要有回显即可）
CONSOLE_CHECKS = [
    ("/ac help", ["指令帮助"], False),
    ("/ac stats", ["检测统计"], False),
    ("/ac reports", ["待处理举报"], False),
    ("/ac reload", ["已重新加载"], False),
    ("/ac profile __ci_no_such_player__", ["只有玩家", "不在线"], True),
    ("/ac totally-bogus-subcommand", ["未知子命令"], False),
    # 已下线能力不得复活：这两个子命令随 Web 面板 / 观察者回放一并移除，
    # 必须回落到「未知子命令」——若有人把分支加回来，这里会立刻变红。
    ("/ac genpwd ci_probe", ["未知子命令"], False),
    ("/ac replay status", ["未知子命令"], False),
]


# --------------------------------------------------------------------------- 基础工具

def log(msg: str) -> None:
    print(msg, flush=True)


def clean(text: str) -> str:
    """去掉 ANSI 转义与 Minecraft § 颜色码，便于做稳定匹配。"""
    return SECTION_RE.sub("", ANSI_RE.sub("", text))


def normalize_exe(path: str) -> str:
    """把 Git Bash 风格路径（/c/Program Files/.../java.exe）转成 Windows 路径。

    直接在 Windows 上把 MSYS 路径交给 CreateProcess 会得到 WinError 2，很难排查。
    """
    if os.name != "nt":
        return path
    m = re.match(r"^/([A-Za-z])/(.*)$", path)
    if m:
        return "%s:\\%s" % (m.group(1).upper(), m.group(2).replace("/", "\\"))
    return path


def ensure_runnable(path: str) -> str:
    """java 路径不存在时给出可读的报错（而不是 CreateProcess 的 WinError 2）。"""
    resolved = normalize_exe(path)
    if os.path.sep in resolved or "/" in resolved:
        if not Path(resolved).exists():
            raise RuntimeError("java 可执行文件不存在: %s" % resolved)
    return resolved


def free_port(preferred: int) -> int:
    """优先用 preferred；被占用则让系统分配一个空闲端口。"""
    for candidate in (preferred, 0):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            try:
                s.bind(("127.0.0.1", candidate))
            except OSError:
                continue
            return s.getsockname()[1]
    raise RuntimeError("无法分配端口")


def http(method: str, url: str, body=None, token: str | None = None, timeout: int = 15):
    """极简 HTTP 客户端，返回 (status, text)。任何异常都不抛出，统一转成 (0, 错误信息)。"""
    data = None
    headers = {"Connection": "close", "Accept": "application/json",
               "User-Agent": USER_AGENT}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001 - 网络异常一律视为探测失败
        return 0, "%s: %s" % (type(e).__name__, e)


def download(url: str, dest: Path, sha256: str | None = None) -> None:
    log("    下载 %s" % url)
    tmp = dest.with_suffix(dest.suffix + ".part")
    h = hashlib.sha256()
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}),
                                timeout=300) as resp, open(tmp, "wb") as fh:
        while True:
            chunk = resp.read(1 << 20)
            if not chunk:
                break
            fh.write(chunk)
            h.update(chunk)
    if sha256 and h.hexdigest().lower() != sha256.lower():
        tmp.unlink(missing_ok=True)
        raise RuntimeError("sha256 校验失败：期望 %s 实际 %s" % (sha256, h.hexdigest()))
    tmp.replace(dest)


def _fill_latest(version: str) -> tuple[str, str | None]:
    """返回 (下载直链, sha256)。v3 列表为倒序，最新构建取 [0]。"""
    status, text = http("GET", FILL_BUILDS_V3.format(v=version), timeout=60)
    if status != 200:
        raise RuntimeError("构建列表查询失败 HTTP %s" % status)
    builds = json.loads(text)
    if isinstance(builds, dict):
        builds = builds.get("builds", [])
    if not builds:
        raise RuntimeError("Paper %s 无可用构建" % version)
    latest = builds[0]
    dl = (latest.get("downloads") or {}).get("server:default") or {}
    url = dl.get("url")
    if not url:
        raise RuntimeError("构建 %s 未提供 server:default 下载直链" % latest.get("id"))
    sha = ((dl.get("checksums") or {}).get("sha256")) or None
    log("    最新构建: build %s (%s)" % (latest.get("id"), dl.get("name")))
    return url, sha


def resolve_server_jar(version: str, cache_dir: Path, explicit: Path | None = None) -> Path:
    """取服务端 jar：优先外部指定，其次缓存，最后按 API 最新构建下载。"""
    if explicit:
        if not explicit.exists():
            raise RuntimeError("指定的服务端 jar 不存在: %s" % explicit)
        log("    使用指定服务端: %s (%.1f MB)" % (explicit.name, explicit.stat().st_size / 1048576))
        return explicit

    cache_dir.mkdir(parents=True, exist_ok=True)
    dest = cache_dir / ("paper-%s.jar" % version)
    if dest.exists() and dest.stat().st_size > 1024 * 1024:
        log("    复用已缓存服务端: %s (%.1f MB)" % (dest.name, dest.stat().st_size / 1048576))
        return dest

    try:
        url, sha = _fill_latest(version)
        download(url, dest, sha)
    except Exception as e:  # noqa: BLE001
        raise RuntimeError("无法获取 Paper %s 服务端（fill.papermc.io/v3）：%s" % (version, e))
    log("    服务端就绪: %s (%.1f MB)" % (dest.name, dest.stat().st_size / 1048576))
    return dest


def patch_plugin_config(text: str) -> str:
    """把 config.yml 里 CI 需要强制打开的开关改成开启态。

    逐行扫描而非 YAML 反序列化：保留原文注释与缩进，避免整份配置被重写后
    和插件内置默认值产生漂移。Web 面板与观察者回放已下线，这里只剩 AI 实验室。
    """
    out, top, sub = [], None, None
    for line in text.splitlines():
        if line and not line[0].isspace() and not line.lstrip().startswith("#"):
            top = line.split(":", 1)[0].strip()
            sub = None
        elif top and line.startswith("  ") and not line.startswith("   ") and line.strip() \
                and not line.lstrip().startswith("#") and line.rstrip().endswith(":"):
            sub = line.strip()[:-1]
        if top == "ailab" and sub is None and re.match(r"^  enabled:", line):
            line = "  enabled: true"
        out.append(line)
    return "\n".join(out) + "\n"


def extract_plugin_config(jar: Path) -> str:
    with zipfile.ZipFile(jar) as zf:
        return zf.read("config.yml").decode("utf-8")


def level_type_for(version: str) -> str:
    parts = version.split(".")
    try:
        minor = int(parts[1])
    except (IndexError, ValueError):
        minor = 21
    return "FLAT" if minor <= 12 else "minecraft:flat"


# 插件打印的是 CraftBukkit 的 NMS 包名（v1_8_R3），不是 MC 版本号本身。
# 断言时把 MC 版本映射成一组可接受的包名前缀，避免把「打印格式变了」误判成「版本识别坏了」。
NMS_TOKENS = {
    "1.8.8": ["v1_8_R3", "1.8.8"],
    "1.21.11": ["v1_21_R", "1.21.11"],
}


def expected_nms_tokens(version: str) -> list[str]:
    if version in NMS_TOKENS:
        return NMS_TOKENS[version]
    parts = version.split(".")
    return ["v%d_%d_R" % (int(parts[0]), int(parts[1])), version]


# --------------------------------------------------------------------------- 服务端进程

def reap_stale_server(workdir: Path) -> None:
    """收尸上一次跑剩下的服务端进程。

    测试运行器被强杀（宿主睡眠后工具调用超时、CI 取消 job）时，Python 死了但
    Windows 上的 java 子进程会活下来继续占着 world 目录与端口，下一次运行就会遇到
    「world 被占用 / 端口被顶到别的号 / 日志互相穿插」这类极难定位的怪现象。
    这里按 PID 文件定向清理，不做全盘 java 猎杀。
    """
    pidfile = workdir / ".e2e-server.pid"
    if not pidfile.exists():
        return
    try:
        pid = int(pidfile.read_text(encoding="ascii").strip())
    except (OSError, ValueError):
        pidfile.unlink(missing_ok=True)
        return
    alive = True
    try:
        os.kill(pid, 0)
    except OSError:
        alive = False
    if alive:
        log("    [WARN] 发现上次未退出的测试服进程 pid=%d，先清理" % pid)
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                os.kill(pid, 15)
        except Exception as e:  # noqa: BLE001
            log("    [WARN] 清理失败：%s" % e)
        time.sleep(2)
    pidfile.unlink(missing_ok=True)


class ServerConsole:
    """驱动真实 MC 服务端：托管 stdin/stdout，支持「发命令→等输出」。"""

    def __init__(self, java: str, jar: Path, workdir: Path, heap: str, logfile: Path):
        self.java, self.jar, self.workdir = java, jar, workdir
        self.heap, self.logfile = heap, logfile
        self.proc: subprocess.Popen | None = None
        self.lines: list[str] = []
        self._lock = threading.Lock()
        self._thread: threading.Thread | None = None
        self.pidfile = Path(logfile).parent / ".e2e-server.pid"

    def start(self) -> None:
        cmd = [self.java, "-Xms512M", "-Xmx" + self.heap, "-Dfile.encoding=UTF-8",
               "-Dcom.mojang.eula.agree=true", "-jar", str(self.jar), "nogui"]
        log("    启动: %s" % " ".join(cmd))
        self.proc = subprocess.Popen(
            cmd, cwd=str(self.workdir), stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=0)
        try:
            self.pidfile.write_text(str(self.proc.pid), encoding="ascii")
        except OSError:
            pass
        self._thread = threading.Thread(target=self._pump, daemon=True)
        self._thread.start()

    def _pump(self) -> None:
        assert self.proc and self.proc.stdout
        with open(self.logfile, "wb") as raw:
            for chunk in iter(lambda: self.proc.stdout.readline(), b""):
                raw.write(chunk)
                raw.flush()
                with self._lock:
                    self.lines.append(chunk.decode("utf-8", "replace").rstrip("\r\n"))

    def snapshot(self) -> list[str]:
        with self._lock:
            return list(self.lines)

    def text(self) -> str:
        return "\n".join(clean(l) for l in self.snapshot())

    def mark(self) -> int:
        with self._lock:
            return len(self.lines)

    def tail_from(self, mark: int) -> str:
        with self._lock:
            return "\n".join(clean(l) for l in self.lines[mark:])

    def send(self, command: str) -> None:
        """发送控制台命令。

        坑：Paper/CraftBukkit 1.8.8 的控制台<b>不接受前导斜杠</b>——带 "/" 会被
        ConsoleCommandHandler 当成未知命令直接回 "Unknown command"，命令根本不进
        Bukkit 的命令分发。去掉斜杠后各版本（1.8.8 / 1.21.11）都正常。
        """
        if not self.proc or self.proc.poll() is not None:
            raise RuntimeError("服务端进程已退出，无法执行命令: %s" % command)
        self.proc.stdin.write((command.lstrip("/") + "\n").encode("utf-8"))
        self.proc.stdin.flush()

    def wait_pattern(self, pattern: str, timeout: int, mark: int = 0) -> str | None:
        """等待日志出现 pattern，返回命中的那一行（已去色）。"""
        rx = re.compile(pattern)
        deadline = time.time() + timeout
        while time.time() < deadline:
            hit = self.tail_from(mark)
            m = rx.search(hit)
            if m:
                return m.group(0)
            if self.proc and self.proc.poll() is not None:
                return None
            time.sleep(0.4)
        return None

    def wait_pattern_in(self, marker: str, timeout: int, mark: int) -> str:
        return self.wait_pattern(marker, timeout, mark) or ""

    def stop(self, grace: int = 75) -> None:
        if not self.proc or self.proc.poll() is not None:
            self.pidfile.unlink(missing_ok=True)
            return
        try:
            self.send("stop")
        except Exception:  # noqa: BLE001
            pass
        try:
            self.proc.wait(timeout=grace)
        except subprocess.TimeoutExpired:
            log("    [WARN] 服务端未在 %ds 内退出，强制结束" % grace)
            self.kill_tree()
        if self._thread:
            self._thread.join(timeout=10)
        self.pidfile.unlink(missing_ok=True)

    def kill_tree(self) -> None:
        if not self.proc:
            return
        pid = self.proc.pid
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            else:
                self.proc.kill()
        except Exception:  # noqa: BLE001
            try:
                self.proc.kill()
            except Exception:  # noqa: BLE001
                pass


# --------------------------------------------------------------------------- 断言框架

class Report:
    def __init__(self, version: str):
        self.version = version
        self.checks: list[dict] = []
        self.started = time.time()

    def add(self, group: str, name: str, ok: bool, detail: str = "") -> bool:
        self.checks.append({"group": group, "name": name, "ok": bool(ok), "detail": detail})
        icon = "PASS" if ok else "FAIL"
        log("    [%s] %s%s" % (icon, name, (" —— " + detail) if (detail and not ok) else ""))
        return ok

    @property
    def failures(self) -> list[dict]:
        return [c for c in self.checks if not c["ok"]]

    @property
    def passed(self) -> bool:
        return not self.failures

    def to_json(self, extra: dict) -> dict:
        return {
            "version": self.version,
            "passed": self.passed,
            "durationSec": round(time.time() - self.started, 1),
            "total": len(self.checks),
            "failed": len(self.failures),
            "checks": self.checks,
            **extra,
        }


# --------------------------------------------------------------------------- 各阶段

def prepare_workdir(args, server_jar: Path, plugin_jar: Path) -> Path:
    server_dir = Path(args.workdir).resolve() / "server"
    if server_dir.exists():
        shutil.rmtree(server_dir, ignore_errors=True)
    (server_dir / "plugins").mkdir(parents=True, exist_ok=True)

    target_jar = server_dir / server_jar.name
    shutil.copy2(server_jar, target_jar)

    # 预热 vanilla 缓存：Paper 首次启动会去 Mojang 下载 mojang_<版本>.jar（约 50MB）。
    # 在受限网络下这一步会静默卡满整个启动超时，表现为「等了 420s 没见 Done」——
    # 看起来像插件把服务端搞挂了，实际只是下载没完成。把 <workdir>/cache 下预置的
    # vanilla jar 复制到服务端自己的 cache 目录，即可离线复现。
    seed = Path(args.workdir).resolve() / "cache" / ("mojang_%s.jar" % args.version)
    if seed.is_file():
        vanilla_dir = server_dir / "cache"
        vanilla_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(seed, vanilla_dir / seed.name)
        log("    复用本地 vanilla 缓存: %s (%.1f MB)" % (seed.name, seed.stat().st_size / 1048576.0))

    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    props = [
        "online-mode=false",
        "server-port=%d" % args.mc_port,
        "level-name=world",
        "level-type=%s" % level_type_for(args.version),
        "spawn-protection=0",
        "generate-structures=false",
        "allow-nether=false",
        "difficulty=peaceful",
        "pvp=false",
        "max-players=5",
        "view-distance=4",
        "enable-command-block=false",
        "white-list=false",
        "motd=CI E2E",
    ]
    (server_dir / "server.properties").write_text("\n".join(props) + "\n", encoding="utf-8")

    shutil.copy2(plugin_jar, server_dir / "plugins" / plugin_jar.name)
    data_dir = server_dir / "plugins" / "AdvancedAntiCheat"
    data_dir.mkdir(parents=True, exist_ok=True)
    cfg = patch_plugin_config(extract_plugin_config(plugin_jar))
    (data_dir / "config.yml").write_text(cfg, encoding="utf-8")
    log("    测试服目录就绪: %s" % server_dir)
    return server_dir


def phase_startup(rep: Report, console: ServerConsole, args) -> bool:
    log("  [1/2] 启动健康度")
    hit = console.wait_pattern(r"Done \(", args.timeout)
    if not rep.add("startup", "服务端启动完成（Done）", bool(hit),
                   "等待 %ds 未见 \"Done (\"；最后日志：\n%s" % (args.timeout, tail(console.tail_from(0)))):
        return False

    logs = console.text()
    for name, pattern in STARTUP_REQUIRED:
        rep.add("startup", name, bool(re.search(pattern, logs)),
                "未在日志中找到 /%s/；最后日志：\n%s" % (pattern, tail(logs)))
    for name, pattern in STARTUP_DEFERRED:
        if not rep.add("startup", name, bool(console.wait_pattern(pattern, 90, 0)),
                       "启动后 90s 内未出现 /%s/（该子系统可能未起来，但服务端本身已就绪）" % pattern):
            logs = console.text()
    for name, pattern in STARTUP_FORBIDDEN:
        rep.add("startup", "无「%s」" % name, not re.search(pattern, logs),
                "命中 /%s/，相关行：%s" % (pattern, tail(text_of(logs, pattern))))
    for name, pattern in FATAL_LOG_PATTERNS:
        m = re.search(pattern, logs, re.M)
        rep.add("startup", "无「%s」" % name, not m,
                "命中 /%s/：\n%s" % (pattern, tail(text_of(logs, pattern))))

    # 版本识别必须与本次测试的服务端一致
    m = re.search(r"检测到服务器版本:\s*(\S+)", logs)
    if m:
        seen = m.group(1)
        want = expected_nms_tokens(args.version)
        rep.add("startup", "版本识别 = %s" % args.version,
                any(w in seen for w in want),
                "插件识别到 %s，期望命中 %s（MC %s 对应的 NMS 包名）"
                % (seen, want, args.version))
    return True


def phase_console(rep: Report, console: ServerConsole) -> None:
    log("  [2/2] 控制台命令")
    for command, markers, soft in CONSOLE_CHECKS:
        mark = console.mark()
        try:
            console.send(command)
        except Exception as e:  # noqa: BLE001
            rep.add("console", command, False, "发送失败: %s" % e)
            continue
        out = ""
        # 轮询用「迭代次数」而不是「墙钟截止时间」：宿主若中途睡眠/挂起，墙钟会一次跳过
        # 整整一段，deadline 立刻过期，于是还没读到任何回显就判失败（曾把 /ac stats 误判成缺陷）。
        hard_deadline = time.time() + 180
        for _ in range(60):  # 约 15s 活跃轮询
            out = console.tail_from(mark)
            if any(m in out for m in markers):
                break
            if re.search(r"^\s+at com\.anticheat\.", out, re.M) or "Unknown command" in out:
                break
            if time.time() > hard_deadline:
                break
            time.sleep(0.25)
        # 「Unknown command」= 命令没进 Bukkit 分发（apos 别忽视：它曾掩盖了所有命令真实失败）
        if "Unknown command" in out:
            rep.add("console", command, False,
                    "命令未被识别（Bukkit 直接回 Unknown command）：\n%s" % tail(out))
            continue
        crashed = bool(re.search(r"^\s+at com\.anticheat\.", out, re.M)) or "Could not pass command" in out
        if crashed:
            rep.add("console", command, False, "执行期抛异常：\n%s" % tail(out))
            continue
        if soft:
            # 软断言：这类命令依赖运行环境（Docker/在线玩家），只要不炸且不给「未知子命令」即通过
            rep.add("console", command, "未知子命令" not in out and bool(out.strip()),
                    "无任何回显，或误判为未知子命令：\n%s" % tail(out))
        else:
            rep.add("console", command, any(m in out for m in markers),
                    "期望包含 %s，实际：\n%s" % (markers, tail(out)))


# --------------------------------------------------------------------------- 输出辅助

def tail(text: str, lines: int = 25) -> str:
    rows = text.strip().splitlines()
    return "\n".join("        | " + r for r in rows[-lines:])


def short(payload, limit: int = 300) -> str:
    s = json.dumps(payload, ensure_ascii=False) if not isinstance(payload, str) else payload
    return s if len(s) <= limit else s[:limit] + "..."


def text_of(logs: str, pattern: str) -> str:
    rows = [r for r in logs.splitlines() if re.search(pattern, r)]
    return "\n".join(rows[:15])


# --------------------------------------------------------------------------- main

def parse_args(argv=None):
    p = argparse.ArgumentParser(description="AdvancedAntiCheat 真机端到端测试")
    p.add_argument("--version", required=True, choices=sorted(PAPER_PINNED_BUILD))
    p.add_argument("--plugin", required=True, help="插件 jar 路径")
    p.add_argument("--server-jar", default="", help="本地已有的服务端 jar（离线/复用，跳过下载）")
    p.add_argument("--workdir", required=True, help="测试服务器工作目录")
    p.add_argument("--java", default="java", help="java 可执行文件（1.21.11 需 JDK 21+）")
    p.add_argument("--mc-port", type=int, default=25565)
    p.add_argument("--heap", default="1400M")
    p.add_argument("--timeout", type=int, default=420, help="等待服务端启动完成的秒数")
    p.add_argument("--report", default="", help="JSON 报告输出路径")
    p.add_argument("--keep", action="store_true", help="结束后保留测试服目录")
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    plugin_jar = Path(args.plugin).resolve()
    if not plugin_jar.exists():
        log("插件 jar 不存在: %s" % plugin_jar)
        return 3

    log("=" * 72)
    log("真机 E2E · Paper %s" % args.version)
    log("  插件   : %s" % plugin_jar.name)
    log("  工作区 : %s" % Path(args.workdir).resolve())
    log("=" * 72)

    args.mc_port = free_port(args.mc_port)
    try:
        args.java = ensure_runnable(args.java)
    except Exception as e:  # noqa: BLE001
        log("致命：%s" % e)
        return 3

    workdir = Path(args.workdir).resolve()
    workdir.mkdir(parents=True, exist_ok=True)
    reap_stale_server(workdir)
    cache_dir = workdir / "cache"
    try:
        server_jar = resolve_server_jar(args.version, cache_dir,
                                       Path(args.server_jar).resolve() if args.server_jar else None)
    except Exception as e:  # noqa: BLE001
        log("致命：%s" % e)
        return 3

    server_dir = prepare_workdir(args, server_jar, plugin_jar)
    console = ServerConsole(args.java, server_jar, server_dir, args.heap,
                            workdir / "console.log")
    rep = Report(args.version)
    fatal = False
    try:
        console.start()
        if not phase_startup(rep, console, args):
            fatal = True
        else:
            phase_console(rep, console)
    except Exception as e:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        rep.add("harness", "测试运行器自身未抛异常", False, "%s: %s" % (type(e).__name__, e))
        fatal = True
    finally:
        log("  停止服务端...")
        console.stop()
        if not args.keep and server_dir.exists():
            shutil.rmtree(server_dir, ignore_errors=True)

    extra = {"pluginJar": plugin_jar.name, "mcPort": args.mc_port}
    report = rep.to_json(extra)

    log("")
    log("-" * 72)
    for group in ("startup", "console", "harness"):
        rows = [c for c in rep.checks if c["group"] == group]
        if rows:
            log("  %-8s %d/%d 通过" % (group, sum(1 for c in rows if c["ok"]), len(rows)))
    log("  合计     %d/%d 通过，%d 失败" % (len(rep.checks) - len(rep.failures),
                                          len(rep.checks), len(rep.failures)))
    log("-" * 72)
    if rep.failures:
        log("失败详情：")
        for c in rep.failures:
            log("  [%s] %s" % (c["group"], c["name"]))
            if c["detail"]:
                log(tail(c["detail"], 18))
    log("")

    if args.report:
        out = Path(args.report)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        log("报告已写入: %s" % out)

    if fatal:
        return 3
    return 0 if rep.passed else 1


if __name__ == "__main__":
    sys.exit(main())
