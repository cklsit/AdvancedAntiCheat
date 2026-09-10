#!/bin/bash
set -euo pipefail

# ===== observer container entrypoint =====
# 1. 启动 Xvfb 虚拟显示 :99 1280x720 24bit
# 2. 可选启动 x11vnc (VNC_ENABLE=true)
# 3. 等待 display 就绪
# 4. 启动 observerctl Flask API (后台 8080)
# 5. 前台启动 run-mc.sh（如果 MC 退出则整个容器退出）

XVFB_PID=""
VNC_PID=""
API_PID=""

cleanup() {
    echo "[entrypoint] Caught shutdown signal, cleaning up..."
    set +e
    [ -n "${API_PID}"  ] && kill -TERM "${API_PID}"  2>/dev/null
    [ -n "${VNC_PID}"  ] && kill -TERM "${VNC_PID}"  2>/dev/null
    # 发送信号给所有本脚本产生的子进程（MC / 子 shell）
    pkill -TERM -P $$ 2>/dev/null || true
    sleep 2
    [ -n "${XVFB_PID}" ] && kill -TERM "${XVFB_PID}" 2>/dev/null
    wait 2>/dev/null
    echo "[entrypoint] Exit 0."
    exit 0
}
trap cleanup SIGTERM SIGINT

export DISPLAY="${DISPLAY:-:99}"

# --- 1. 启动 Xorg (Xdummy) 提供真实 GLX/Mesa llvmpipe 上下文 ---
#     Xvfb bookworm 即使 +GLX 也不提供真实 GLX visual (MC 1.8.8 会 No OpenGL context crash)
#     改用 xf86-video-dummy + Xorg + /mc/xorg-dummy.conf + LIBGL_ALWAYS_SOFTWARE=1 llvmpipe
#     需要清理旧锁（上次未正常退出时可能残留）
echo "[entrypoint] Cleaning stale X11 locks for ${DISPLAY} ..."
set +e
DISPNUM="${DISPLAY#:}"
DISPNUM="${DISPNUM%%.*}"
rm -f /tmp/.X${DISPNUM}-lock
rm -f /tmp/.X11-unix/X${DISPNUM}
set -e

echo "[entrypoint] Starting Xorg(dummy) ${DISPLAY} (1280x720x24 +GLX via xorg-dummy.conf + llvmpipe)..."
Xorg "${DISPLAY}" \
    -config /mc/xorg-dummy.conf \
    -noreset \
    -ac \
    -novtswitch \
    -sharevts \
    +extension GLX \
    +extension RENDER \
    +extension RANDR \
    vt7 &
XVFB_PID=$!

# --- 2. 等待 display + GLX 自检 ---
echo "[entrypoint] Waiting for Xorg(dummy) display ready (max 30s)..."
for i in $(seq 1 30); do
    if xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1; then
        echo "[entrypoint] Xorg(dummy) ready after ${i}s."
        break
    fi
    if ! kill -0 "${XVFB_PID}" 2>/dev/null; then
        echo "[entrypoint] ERROR: Xorg already exited. Abort. Xorg logs:" >&2
        cat /var/log/Xorg.${DISPNUM}.log >&2 2>/dev/null || true
        exit 1
    fi
    sleep 1
done
if ! xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1; then
    echo "[entrypoint] ERROR: Xorg(dummy) failed to become ready in 30s." >&2
    exit 1
fi

# ---- 伪造 xrandr：LWJGL 2.9.4 XRandR.getResolutions 会解析 xrandr -q 输出，
#      Xdummy 输出经常为空/格式不对，导致 NPE crash。在 PATH 最前面塞一个 fake xrandr。
mkdir -p /tmp/fakebin
cat > /tmp/fakebin/xrandr <<'EOF'
#!/bin/bash
# Fake xrandr for LWJGL 2.9.4 on headless Xdummy/Xvfb.
# Output matches what XRandR.getResolutions expects: "name connected WxH+x+y ..." followed by modes.
if [ "$1" = "-q" ] || [ -z "$1" ]; then
    echo "Screen 0: minimum 320 x 200, current 1280 x 720, maximum 8192 x 8192"
    echo "default connected primary 1280x720+0+0 0mm x 0mm"
    echo "   1280x720       60.00*+"
    echo "   1920x1080      60.00"
    echo "   1024x768       60.00"
    echo "   800x600        60.00"
    echo "   640x480        60.00"
else
    /usr/bin/xrandr "$@" 2>/dev/null || true
fi
EOF
chmod +x /tmp/fakebin/xrandr
export PATH="/tmp/fakebin:${PATH}"
echo "[entrypoint] fake xrandr injected at /tmp/fakebin/xrandr, PATH=${PATH}"

# ---- GLX/Mesa llvmpipe 自检 ----
echo "[entrypoint] GLX vendor/renderer check:"
if command -v glxinfo >/dev/null 2>&1; then
    glxinfo -B 2>&1 | head -20 || echo "(glxinfo non-fatal err)"
else
    echo "(glxinfo not installed; skipping)"
fi

# --- 3. 可选启动 x11vnc ---
if [ "${VNC_ENABLE,,}" = "true" ]; then
    echo "[entrypoint] VNC_ENABLE=true -> starting x11vnc on rfbport 5900..."
    x11vnc \
        -display "${DISPLAY}" \
        -rfbport 5900 \
        -N \
        -forever \
        -shared \
        -nopw \
        -quiet \
        -bg -o /tmp/x11vnc.log &
    VNC_PID=$!
    sleep 1
else
    echo "[entrypoint] VNC not enabled (set VNC_ENABLE=true to activate)."
fi

# --- 4. 启动 observerctl Flask API ---
echo "[entrypoint] Starting observerctl Flask API on 0.0.0.0:8080..."
python3 /mc/observerctl.py > /tmp/observerctl.log 2>&1 &
API_PID=$!
sleep 2
if ! kill -0 "${API_PID}" 2>/dev/null; then
    echo "[entrypoint] ERROR: observerctl exited immediately. Logs:" >&2
    cat /tmp/observerctl.log >&2 || true
    exit 1
fi

# --- 5. 前台启动 MC 包装脚本 ---
echo "[entrypoint] Starting Minecraft client wrapper (run-mc.sh)..."
set +e
# 前台执行 MC；如果它退出，cleanup 会被触发（收到信号时）
# 但若是 MC 本身退出也应把容器标记为退出，让 compose restart 决定是否重启。
/mc/run-mc.sh
MC_RC=$?
set -e
echo "[entrypoint] run-mc.sh exited with code ${MC_RC}. Stopping container."

cleanup
