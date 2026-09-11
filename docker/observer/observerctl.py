#!/usr/bin/env python3
"""
observerctl.py  –  观察者客户端 HTTP 控制 API
监听 0.0.0.0:8080，供外部 Bukkit 插件 / Web 面板调用：

  GET  /status              查看 Xvfb / MC / ffmpeg 状态
  POST /start?uuid=<uuid>   开始对 Xvfb 画面录制 HLS 到 /hls/<uuid>/
  POST /stop?uuid=<uuid>    结束录制 + 合成 /hls/<uuid>.mp4
  POST /connect             用 xdotool 走菜单加入服务器（旧路径，--server 可用后一般不再需要）
  POST /mc/up               按需进服：要求 MC 客户端进入服务器
  POST /mc/down             离线：终止 MC 客户端（容器与 observerctl 仍常驻）
"""

import os
import re
import shlex
import signal
import subprocess
import sys
import time
from pathlib import Path

from flask import Flask, jsonify, request

# ---------- 全局状态 ----------
FFMPEG_PID: int | None = None
FFMPEG_PROC: subprocess.Popen | None = None
CURRENT_UUID: str | None = None

OBSERVER_ID: str = os.environ.get("OBSERVER_ID", "")

UUID_RE = re.compile(r"^[A-Fa-f0-9\-]{4,128}$")
SESSION_ID_RE = re.compile(r"^[a-zA-Z0-9_\-]+$")

HLS_ROOT = Path("/hls")

# 按需进服标志文件：存在 = 要求 MC 客户端进入服务器。
# run-mc.sh 轮询该文件决定是否拉起/终止 MC 进程，由 /mc/up 与 /mc/down 写入/删除。
# 这样观察者常态下不进服，只在管理员观看直播时才加入服务器。
MC_WANT_FLAG = Path(os.environ.get("MC_ONDEMAND_FLAG", "/tmp/mc_wanted"))

# /start 返回前等待首个切片 + playlist 落盘的最长秒数。
# ffmpeg 使用 -hls_time 2，正常约 2s 产出首个切片；给足余量以覆盖
# 容器冷启动 / CPU 抢占等抖动。等待期间若 ffmpeg 退出则立即报错。
PLAYLIST_READY_TIMEOUT = 15.0

app = Flask(__name__)


# ---------- 工具函数 ----------
def _run_cmd(args, *, timeout: int = 10) -> int:
    """subprocess.run + timeout，返回退出码。失败不抛异常。"""
    try:
        p = subprocess.run(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=timeout)
        return p.returncode
    except Exception:
        return -1


def _xvfb_ok() -> bool:
    return _run_cmd(["xdpyinfo", "-display", os.environ.get("DISPLAY", ":99")]) == 0


def _mc_running() -> bool:
    return _run_cmd(["pgrep", "-f", "net.minecraft.client.main.Main"]) == 0


def _find_mc_window() -> str | None:
    """通过 xdotool 查找 Minecraft 1.8.8 窗口 ID。"""
    try:
        proc = subprocess.run(
            ["xdotool", "search", "--name", "Minecraft 1.8.8"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            timeout=5,
        )
        if proc.returncode == 0:
            ids = [x.strip() for x in proc.stdout.strip().splitlines() if x.strip()]
            return ids[-1] if ids else None
    except Exception:
        pass
    return None


def _send_key(window_id: str, key: str) -> bool:
    try:
        rc = subprocess.run(
            ["xdotool", "key", "--window", window_id, key],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=5,
        ).returncode
        return rc == 0
    except Exception:
        return False


def _type_text(window_id: str, text: str) -> bool:
    try:
        rc = subprocess.run(
            ["xdotool", "type", "--window", window_id, "--delay", "30", text],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
        ).returncode
        return rc == 0
    except Exception:
        return False


def _click(window_id: str, x: int, y: int) -> bool:
    try:
        rc = subprocess.run(
            ["xdotool", "mousemove", "--window", window_id, "--sync", str(x), str(y),
             "click", "1"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=5,
        ).returncode
        return rc == 0
    except Exception:
        return False


def _ffmpeg_running() -> bool:
    global FFMPEG_PID, FFMPEG_PROC
    if FFMPEG_PID is None:
        return False
    if FFMPEG_PROC is not None and FFMPEG_PROC.poll() is None:
        return True
    # 兜底：检查 /proc
    try:
        os.kill(FFMPEG_PID, 0)
        return True
    except (ProcessLookupError, PermissionError, OSError):
        FFMPEG_PID = None
        FFMPEG_PROC = None
        return False


def _uuid_valid(u: str | None) -> bool:
    return bool(u) and bool(UUID_RE.match(u))


def _session_id_valid(s: str | None) -> bool:
    return bool(s) and bool(SESSION_ID_RE.match(s))


def _list_sessions():
    """扫描 /hls 下的会话目录，返回 [{id, segmentCount, sizeBytes}]。"""
    sessions = []
    try:
        if not HLS_ROOT.is_dir():
            return sessions
        for entry in HLS_ROOT.iterdir():
            if not entry.is_dir():
                continue
            sid = entry.name
            if not _session_id_valid(sid):
                continue
            m3u8 = entry / "index.m3u8"
            if not m3u8.is_file():
                continue
            # 统计 .ts 切片数量和总大小
            seg_count = 0
            total_bytes = 0
            for f in entry.iterdir():
                if f.is_file() and f.suffix == ".ts":
                    seg_count += 1
                    try:
                        total_bytes += f.stat().st_size
                    except OSError:
                        pass
            sessions.append({
                "id": sid,
                "segmentCount": seg_count,
                "sizeBytes": total_bytes,
            })
    except Exception:
        pass
    return sessions


def _probe_duration_and_size(mp4_path: Path, m3u8_path: Path):
    """优先用 ffprobe 取 sizeBytes 和 durationSec，失败则 fallback。"""
    size_bytes = 0
    duration_sec = 0.0
    try:
        size_bytes = mp4_path.stat().st_size
    except OSError:
        size_bytes = 0

    # 尝试 ffprobe
    try:
        probe_cmd = [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(mp4_path),
        ]
        proc = subprocess.run(
            probe_cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            timeout=15,
        )
        if proc.returncode == 0:
            out = (proc.stdout.decode("utf-8", errors="replace") or "").strip()
            if out:
                try:
                    duration_sec = float(out)
                except ValueError:
                    duration_sec = 0.0
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        pass

    # fallback：解析 m3u8 的 #EXTINF 累加
    if duration_sec <= 0 and m3u8_path.is_file():
        try:
            text = m3u8_path.read_text(encoding="utf-8", errors="replace")
            for line in text.splitlines():
                line = line.strip()
                if line.startswith("#EXTINF:"):
                    try:
                        val = line.split(":", 1)[1].split(",", 1)[0].strip()
                        duration_sec += float(val)
                    except (ValueError, IndexError):
                        pass
        except OSError:
            pass

    return size_bytes, max(0.0, duration_sec)


# ---------- 路由 ----------
@app.route("/status", methods=["GET"])
def route_status():
    xvfb = "ok" if _xvfb_ok() else "error"
    if _mc_running():
        mc_status = "running"
    elif xvfb == "error":
        mc_status = "error"
    else:
        # MC 进程不在，可能是启动中或崩溃
        mc_status = "stopped"
    ffmpeg_pid = FFMPEG_PID if _ffmpeg_running() else None
    sessions = _list_sessions()
    return jsonify({
        "mc_status": mc_status,
        "xvfb": xvfb,
        "ffmpeg_pid": ffmpeg_pid,
        "observer_id": OBSERVER_ID,
        "sessions": sessions,
        "ready": True,
        # 按需进服：是否已被要求进服（标志文件存在）
        "mc_wanted": MC_WANT_FLAG.exists(),
    })


@app.route("/start", methods=["POST"])
def route_start():
    global FFMPEG_PID, FFMPEG_PROC, CURRENT_UUID

    try:
        uuid = request.args.get("uuid", "").strip()
        if not _uuid_valid(uuid):
            return jsonify({"ok": False, "error": "invalid/missing uuid query param"}), 400

        out_dir = Path(f"/hls/{uuid}")
        try:
            out_dir.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            return jsonify({"ok": False, "error": f"mkdir failed: {e}"}), 500

        # 清理上一次没正常结束的 ffmpeg
        if _ffmpeg_running():
            try:
                _kill_ffmpeg()
            except Exception:
                pass

        # 清掉上一轮会话残留的切片与播放列表。
        # 新一次录制会从 seg_00000 重新编号，若不清旧片段：
        #   1) 目录里会同时留着新旧两套切片，难以判断当前画面是哪一段；
        #   2) 前端/浏览器可能命中缓存中的旧 index.m3u8，配合已停止的流
        #      会表现为"画面定格在上一段录像的最后一帧"。
        for _old in list(out_dir.glob("seg_*.ts")):
            try:
                _old.unlink()
            except OSError:
                pass
        _old_pl = out_dir / "index.m3u8"
        if _old_pl.exists():
            try:
                _old_pl.unlink()
            except OSError:
                pass

        display = os.environ.get("DISPLAY", ":99")
        seg_template = str(out_dir / "seg_%05d.ts")
        playlist_path = out_dir / "index.m3u8"
        playlist = str(playlist_path)

        cmd = [
            "ffmpeg", "-y",
            "-f", "x11grab",
            "-video_size", "1280x720",
            "-framerate", "30",
            "-i", display,
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "28",
            "-g", "60",
            "-sc_threshold", "0",
            "-f", "hls",
            "-hls_time", "2",
            "-hls_list_size", "0",
            "-hls_flags", "independent_segments",
            "-hls_segment_filename", seg_template,
            playlist,
        ]
        proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # 稍等确认没秒退
        time.sleep(0.8)
        if proc.poll() is not None:
            return jsonify({"ok": False,
                            "error": f"ffmpeg exited immediately code={proc.returncode}"}), 500

        # 等待 playlist 真正就绪再返回。
        # 若不等待，插件会在 playlist 尚未落盘时就推送 observer_ready，
        # 前端立即请求 /hls/<uuid>/index.m3u8 得到 404（或 SPA 回落成 HTML），
        # 表现为画面长期卡在"直播流初始化中"。
        playlist_ready = False
        deadline = time.time() + PLAYLIST_READY_TIMEOUT
        while time.time() < deadline:
            if proc.poll() is not None:
                return jsonify({"ok": False,
                                "error": f"ffmpeg exited while waiting playlist, code={proc.returncode}"}), 500
            try:
                # 需要 playlist + 至少一个切片，避免只拿到 #EXTM3U 空表
                if playlist_path.is_file() and any(out_dir.glob("seg_*.ts")):
                    playlist_ready = True
                    break
            except OSError:
                pass
            time.sleep(0.25)

        FFMPEG_PROC = proc
        FFMPEG_PID = proc.pid
        CURRENT_UUID = uuid

        return jsonify({"ok": True, "ffmpeg_pid": proc.pid, "uuid": uuid,
                        "playlist": playlist, "playlist_ready": playlist_ready})
    except Exception as e:
        return jsonify({"ok": False, "error": f"start: {e.__class__.__name__}: {e}"}), 500


def _kill_ffmpeg():
    global FFMPEG_PID, FFMPEG_PROC
    if FFMPEG_PROC is not None and FFMPEG_PROC.poll() is None:
        try:
            FFMPEG_PROC.send_signal(signal.SIGINT)
        except (ProcessLookupError, OSError):
            pass
        try:
            FFMPEG_PROC.wait(timeout=5)
        except subprocess.TimeoutExpired:
            try:
                FFMPEG_PROC.kill()
            except Exception:
                pass
            try:
                FFMPEG_PROC.wait(timeout=3)
            except Exception:
                pass
    elif FFMPEG_PID is not None:
        try:
            os.kill(FFMPEG_PID, signal.SIGINT)
        except (ProcessLookupError, OSError):
            pass
        deadline = time.time() + 5
        while time.time() < deadline:
            try:
                os.kill(FFMPEG_PID, 0)
            except ProcessLookupError:
                break
            time.sleep(0.3)
        else:
            try:
                os.kill(FFMPEG_PID, signal.SIGKILL)
            except Exception:
                pass


@app.route("/connect", methods=["POST"])
def route_connect():
    """自动操作 MC 1.8.8 客户端加入服务器。

    由于 1.8.8 Main 类不支持 --server 参数，容器启动后 MC 会停在主菜单。
    本端点通过 xdotool 模拟鼠标/键盘：
      1) 按 Esc 确保回到主菜单/游戏菜单
      2) 点击 Multiplayer
      3) 点击 Direct Connect
      4) 在 Server Address 输入框填入 MC_SERVER_HOST:MC_SERVER_PORT
      5) 按 Tab 聚焦 Join Server 后回车

    坐标默认按 1280x720 客户端窗口；可通过环境变量覆盖。
    """
    try:
        if not _mc_running():
            return jsonify({"ok": False, "error": "minecraft not running"}), 503

        window_id = _find_mc_window()
        if not window_id:
            return jsonify({"ok": False, "error": "minecraft window not found"}), 503

        host = os.environ.get("MC_SERVER_HOST", "")
        port = os.environ.get("MC_SERVER_PORT", "25565")
        if not host:
            return jsonify({"ok": False, "error": "MC_SERVER_HOST not set"}), 500
        address = f"{host}:{port}"

        # 坐标默认：1280x720 窗口，按钮大致居中
        def _int_env(key: str, default: int) -> int:
            try:
                return int(os.environ.get(key, str(default)))
            except ValueError:
                return default

        mp_x = _int_env("CONNECT_MP_X", 640)
        mp_y = _int_env("CONNECT_MP_Y", 318)
        dc_x = _int_env("CONNECT_DC_X", 640)
        dc_y = _int_env("CONNECT_DC_Y", 380)

        # 1) 先切到窗口并按 Esc，尝试回到主菜单
        subprocess.run(
            ["xdotool", "windowactivate", "--sync", window_id],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5
        )
        time.sleep(0.2)
        for _ in range(3):
            _send_key(window_id, "Escape")
            time.sleep(0.3)

        # 2) 主菜单 -> Multiplayer
        if not _click(window_id, mp_x, mp_y):
            return jsonify({"ok": False, "error": "failed to click Multiplayer"}), 500
        time.sleep(1.5)

        # 3) 多人游戏界面 -> Direct Connect
        if not _click(window_id, dc_x, dc_y):
            return jsonify({"ok": False, "error": "failed to click Direct Connect"}), 500
        time.sleep(0.8)

        # 4) Direct Connect 界面：输入框通常已自动聚焦；先清空再输入地址
        _send_key(window_id, "ctrl+a")
        _send_key(window_id, "Delete")
        time.sleep(0.1)
        if not _type_text(window_id, address):
            return jsonify({"ok": False, "error": "failed to type server address"}), 500
        time.sleep(0.2)

        # 5) Tab 聚焦 Join Server，回车加入
        _send_key(window_id, "Tab")
        time.sleep(0.1)
        _send_key(window_id, "Return")

        return jsonify({"ok": True, "address": address, "message": "connect sequence sent"})
    except Exception as e:
        return jsonify({"ok": False, "error": f"connect: {e.__class__.__name__}: {e}"}), 500


@app.route("/mc/up", methods=["POST"])
def route_mc_up():
    """要求 MC 客户端进入服务器（按需进服）。

    只写标志文件，run-mc.sh 会在 ~2s 内拉起客户端；客户端带 --server 启动，
    因此会直接连服并跳过主菜单。调用方（插件）随后轮询 /status 与服务器
    玩家列表确认登录成功。
    """
    try:
        MC_WANT_FLAG.parent.mkdir(parents=True, exist_ok=True)
        MC_WANT_FLAG.write_text(str(time.time()), encoding="utf-8")
        return jsonify({
            "ok": True,
            "wanted": True,
            "mc_running": _mc_running(),
            "flag": str(MC_WANT_FLAG),
        })
    except Exception as e:
        return jsonify({"ok": False, "error": f"mc/up: {e.__class__.__name__}: {e}"}), 500


@app.route("/mc/down", methods=["POST"])
def route_mc_down():
    """要求 MC 客户端退出服务器（离线）。

    清标志 + 终止 MC 进程；同时停掉可能在跑的 ffmpeg，避免留下无画面的录制。
    进程退出后容器仍保持运行（observerctl / Xorg 常驻），随时可被再次拉起。
    """
    try:
        try:
            MC_WANT_FLAG.unlink()
        except FileNotFoundError:
            pass

        if _ffmpeg_running():
            try:
                _kill_ffmpeg()
            except Exception:
                pass

        _run_cmd(["pkill", "-f", "net.minecraft.client.main.Main"], timeout=10)
        # 给进程一点退出时间，让调用方拿到较准确的 mc_running
        time.sleep(1.0)

        return jsonify({
            "ok": True,
            "wanted": False,
            "mc_running": _mc_running(),
            "flag": str(MC_WANT_FLAG),
        })
    except Exception as e:
        return jsonify({"ok": False, "error": f"mc/down: {e.__class__.__name__}: {e}"}), 500


@app.route("/stream/kill", methods=["POST"])
def route_stream_kill():
    """强制结束当前录制且**不做 mp4 合成**，用于清理残留/孤儿流。

    场景：服务端重启后本插件不再认得容器里仍在运行的 ffmpeg，于是留下一个无人观看、
    却在持续写盘的孤儿流（1280x720@30 约 5GB/天）。插件侧的看门狗会调用本接口清理。
    与 /stop 的区别：/stop 会等待 concat 合成 mp4（最长 35s）并返回路径，这里只杀进程。
    """
    killed = False
    if _ffmpeg_running():
        try:
            _kill_ffmpeg()
            killed = True
        except Exception as e:
            return jsonify({"ok": False, "error": f"kill: {e.__class__.__name__}: {e}"}), 500
    return jsonify({"ok": True, "killed": killed})


@app.route("/stop", methods=["POST"])
def route_stop():
    global FFMPEG_PID, FFMPEG_PROC, CURRENT_UUID
    try:
        uuid = request.args.get("uuid", "").strip()
        if not _uuid_valid(uuid):
            return jsonify({"ok": False, "error": "invalid/missing uuid query param"}), 400

        # 结束 ffmpeg（无论 uuid 匹配与否，一次只允许一个录制，先杀再合成）
        if _ffmpeg_running():
            _kill_ffmpeg()
        # 清空状态
        FFMPEG_PROC = None
        FFMPEG_PID = None
        CURRENT_UUID = None

        playlist = Path(f"/hls/{uuid}/index.m3u8")
        mp4_out = Path(f"/hls/{uuid}.mp4")

        if not playlist.exists():
            return jsonify({"ok": False,
                            "error": f"playlist not found: {playlist}"}), 404

        cmd = [
            "ffmpeg", "-y",
            "-i", str(playlist),
            "-c", "copy",
            "-movflags", "+faststart",
            str(mp4_out),
        ]
        try:
            proc = subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=60)
        except subprocess.TimeoutExpired:
            return jsonify({"ok": False, "error": "ffmpeg mux mp4 timed out after 60s"}), 500

        if proc.returncode != 0:
            msg = (proc.stderr.decode("utf-8", errors="replace") or "").strip().splitlines()
            tail = "\n".join(msg[-5:])
            return jsonify({"ok": False,
                            "error": f"ffmpeg mux exit {proc.returncode}",
                            "stderr_tail": tail}), 500

        return jsonify({"ok": True, "mp4": str(mp4_out), "uuid": uuid})
    except Exception as e:
        return jsonify({"ok": False, "error": f"stop: {e.__class__.__name__}: {e}"}), 500


@app.route("/concat", methods=["POST"])
def route_concat():
    """拼接 HLS m3u8 为 mp4，写入 /hls/archive/。"""
    try:
        body = request.get_json(silent=True) or {}
        session_id = (body.get("sessionId") or "").strip()
        out_filename = (body.get("outFilename") or "").strip()

        # 1. 输入校验
        if not _session_id_valid(session_id):
            return jsonify({"ok": False, "error": "invalid/missing sessionId"}), 400

        # 2. 检查 m3u8 是否存在
        source_m3u8 = HLS_ROOT / session_id / "index.m3u8"
        if not source_m3u8.is_file():
            return jsonify({
                "ok": False,
                "error": f"playlist not found: {source_m3u8}"
            }), 404

        # 3. 输出目录
        out_dir = HLS_ROOT / "archive"
        try:
            out_dir.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            return jsonify({"ok": False, "error": f"mkdir archive failed: {e}"}), 500

        # 4. 输出文件名
        if not out_filename:
            out_filename = session_id + ".mp4"
        else:
            # 简单安全校验：禁止路径穿越字符，且必须以 .mp4 结尾
            if ".." in out_filename or "/" in out_filename or "\\" in out_filename:
                return jsonify({"ok": False, "error": "invalid outFilename: path traversal not allowed"}), 400
            if not out_filename.lower().endswith(".mp4"):
                out_filename += ".mp4"
        out_file = out_dir / out_filename

        # 5. 调 ffmpeg 拼接
        cmd = [
            "ffmpeg", "-y",
            "-allowed_extensions", "ALL",
            "-i", str(source_m3u8),
            "-c", "copy",
            "-movflags", "+faststart",
            str(out_file),
        ]
        try:
            proc = subprocess.run(
                cmd,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.PIPE,
                timeout=180,
            )
        except subprocess.TimeoutExpired:
            return jsonify({
                "ok": False,
                "error": "ffmpeg concat timed out after 180s"
            }), 500

        if proc.returncode != 0:
            msg = (proc.stderr.decode("utf-8", errors="replace") or "").strip().splitlines()
            tail = "\n".join(msg[-5:])
            return jsonify({
                "ok": False,
                "error": f"ffmpeg concat exit {proc.returncode}",
                "stderr_tail": tail,
            }), 500

        # 6. 取 sizeBytes 和 durationSec
        size_bytes, duration_sec = _probe_duration_and_size(out_file, source_m3u8)

        return jsonify({
            "ok": True,
            "mp4Path": "/hls/archive/" + out_filename,
            "sizeBytes": size_bytes,
            "durationSec": duration_sec,
        })

    except Exception as e:
        return jsonify({
            "ok": False,
            "error": f"concat: {e.__class__.__name__}: {e}"
        }), 500


@app.errorhandler(404)
def _404(_e):
    return jsonify({"ok": False, "error": "not_found"}), 404


@app.errorhandler(405)
def _405(_e):
    return jsonify({"ok": False, "error": "method_not_allowed"}), 405


def main() -> int:
    port = int(os.environ.get("OBSERVERCTL_PORT", "8080"))
    host = os.environ.get("OBSERVERCTL_HOST", "0.0.0.0")
    # threaded=True 以便并发请求（例如 /status 与 /stop 同时到来）
    app.run(host=host, port=port, threaded=True, debug=False, use_reloader=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
