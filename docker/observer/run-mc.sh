#!/bin/bash
set -euo pipefail

# ===== Minecraft 1.8.8 客户端启动包装器（常驻 + 断线自愈版） =====
# - 无限重启：observer 客户端必须保持在线，以便随时被插件调度录制。
#   无论 MC 崩溃、被 kill、还是正常退出（code 0），都在短暂等待后重新拉起。
# - 不再有 300s 强制超时：assets/libraries 已在镜像内就绪，MC 应长时间运行。
# - 崩溃退避：若进程存活不足 60s 即退出（启动即崩），等待时间 3s 起步逐步 +3s，
#   上限 30s，避免崩溃风暴打爆 CPU；一次存活超过 60s 后重置退避。
# - 1.8.8 Main 类支持 --server/--port：启动即自动连接服务器，跳过主菜单 GUI。
# - 断线自愈看门狗：客户端断线后只会退回主菜单且进程不退出，--server 不会重试，
#   这会导致服务器重启/重载后 observer 永久停在主菜单（表现为"登录不上"）。
#   看门狗通过内核 TCP 表检测连接状态，必要时重启 MC 进程以重新连接。
# - JVM 输出同时写入 /tmp/mc.log，便于排查。

cd /mc

CLASSPATH="/mc/client.jar:/mc/lib/*"
NATIVE_DIR="/mc/lib/natives"
MAIN_CLASS="net.minecraft.client.main.Main"

# 环境变量校验（缺失立即退出）
: "${USERNAME:?USERNAME env is required}"
: "${UUID:?UUID env is required}"
: "${MC_SERVER_HOST:?MC_SERVER_HOST env is required}"
: "${MC_SERVER_PORT:?MC_SERVER_PORT env is required}"

STABLE_SECS=60
BACKOFF=3
MAX_BACKOFF=30

mkdir -p /mc/assets
touch /tmp/mc.log

# ------------------------------------------------------------------
# 断线自动重连看门狗
# ------------------------------------------------------------------
# 将 "a.b.c.d:port" 换算成 /proc/net/tcp 使用的十六进制表示：
#   - IPv4 表：小端十六进制，如 192.168.1.132:25565 -> 8401A8C0:63DD
#   - IPv6 表：IPv4-mapped 形式（Java 默认走双栈 socket，实际连接在 tcp6 里）
IFS=. read -r _o1 _o2 _o3 _o4 <<< "$MC_SERVER_HOST"
PEER4=$(printf '%02X%02X%02X%02X' "$_o4" "$_o3" "$_o2" "$_o1")
PORTHEX=$(printf '%04X' "$MC_SERVER_PORT")
PEER_V4="${PEER4}:${PORTHEX}"
PEER_V6="0000000000000000FFFF0000${PEER4}:${PORTHEX}"

# 是否存在与目标服务器的 ESTABLISHED 连接（状态 01）
have_mc_conn() {
    awk -v p4="$PEER_V4" -v p6="$PEER_V6" \
        'FNR>1 && $4=="01" && ($3==p4 || $3==p6) {n++} END{exit (n>0)?0:1}' \
        /proc/net/tcp /proc/net/tcp6 2>/dev/null
}

# 服务器端口是否可达（避免维护窗口期间误杀客户端）
server_reachable() {
    timeout 3 bash -c "exec 3<>/dev/tcp/${MC_SERVER_HOST}/${MC_SERVER_PORT}" >/dev/null 2>&1
}

watchdog_loop() {
    local miss=0 last_pid="" proc_start=0 pid age
    while true; do
        sleep 20
        pid=$(pgrep -f "$MAIN_CLASS" 2>/dev/null | head -n1 || true)

        # 进程不在（由主循环负责拉起）：重置状态
        if [ -z "$pid" ]; then
            miss=0; last_pid=""; proc_start=0
            continue
        fi

        # 进程刚重启：记录起始时间并给出启动宽限
        if [ "$pid" != "$last_pid" ]; then
            last_pid="$pid"
            proc_start=$(date +%s)
            miss=0
            continue
        fi

        # 启动宽限期内（MC 初始化 + 首次连服需要时间）不做判断
        age=$(( $(date +%s) - proc_start ))
        if [ "$age" -lt 180 ]; then
            miss=0
            continue
        fi

        # 已连接：一切正常
        if have_mc_conn; then
            miss=0
            continue
        fi

        # 未连接但服务器不可达：可能服务器在维护，不计入（等服务器恢复后再判定）
        if ! server_reachable; then
            miss=0
            continue
        fi

        # 服务器可达却持续无连接 -> 客户端滞留主菜单，累计并重启
        miss=$((miss + 1))
        if [ "$miss" -ge 6 ]; then
            echo "[watchdog] 服务器可达但无已建立连接达 $((miss * 20))s，重启 MC 以重连" \
                | tee -a /tmp/mc.log
            pkill -f "$MAIN_CLASS" >/dev/null 2>&1 || true
            miss=0; last_pid=""; proc_start=0
            sleep 15
        fi
    done
}

watchdog_loop &
WATCHDOG_PID=$!
echo "[run-mc] 断线自愈看门狗已启动 (pid=${WATCHDOG_PID})，目标 ${MC_SERVER_HOST}:${MC_SERVER_PORT}" \
    | tee -a /tmp/mc.log

echo "[run-mc] 常驻模式启动：MC 退出后自动重启。MC_SERVER_HOST=${MC_SERVER_HOST}:${MC_SERVER_PORT}" \
    | tee -a /tmp/mc.log

while true; do
    echo "[run-mc] ========== 启动 MC $(date '+%F %T') ==========" | tee -a /tmp/mc.log
    echo "[run-mc] USERNAME=${USERNAME} UUID=${UUID}" | tee -a /tmp/mc.log
    echo "[run-mc] NATIVE_DIR=${NATIVE_DIR}" | tee -a /tmp/mc.log

    START_TS=$(date +%s)

    set +e
    # 环境变量说明：
    #   LIBGL_ALWAYS_SOFTWARE=1        强制 llvmpipe 软件 GL（无 GPU 必需）
    #   GALLIUM_DRIVER=llvmpipe        明确选择 Mesa llvmpipe 后端
    #   MESA_GL_VERSION_OVERRIDE=3.0   让 MC 1.8.8 的 GL 2.1 请求不要被 Mesa 拒绝
    # JVM 说明：使用镜像内 Temurin JRE 8（/mc/jdk8/bin/java）。
    #   MC 1.8.8 的 LWJGL2/netty4.0.23 是 Java 8 时代产物，在 Java 17 下
    #   direct-buffer 访问失败（Unable to access address of buffer）导致连服即断；
    #   Java 8 无此问题（勿改回 17）。
    # assetIndex 说明：MC 1.8.x 系列共用资源索引 "1.8"（不是版本号 1.8.8），
    #   写成 1.8.8 会导致 "Can't find the resource index file" 并连服失败。
    env LIBGL_ALWAYS_SOFTWARE=1 \
        GALLIUM_DRIVER=llvmpipe \
        MESA_GL_VERSION_OVERRIDE=3.0 \
        /mc/jdk8/bin/java \
        -Xmx1G \
        -Xms512M \
        -Dorg.lwjgl.librarypath="$NATIVE_DIR" \
        -Dnet.java.games.input.librarypath="$NATIVE_DIR" \
        -Djava.library.path="$NATIVE_DIR" \
        -Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true \
        -cp "$CLASSPATH" \
        "$MAIN_CLASS" \
        --version "1.8.8" \
        --username "$USERNAME" \
        --uuid "$UUID" \
        --accessToken "offline" \
        --userProperties "{}" \
        --gameDir "/mc" \
        --assetsDir "/mc/assets" \
        --assetIndex "1.8" \
        --server "$MC_SERVER_HOST" \
        --port "$MC_SERVER_PORT" \
        2>&1 | tee -a /tmp/mc.log
    MC_RC=${PIPESTATUS[0]}
    set -e

    ELAPSED=$(( $(date +%s) - START_TS ))
    echo "[run-mc] java exited code=${MC_RC} after ${ELAPSED}s" | tee -a /tmp/mc.log

    # 存活超过 60s = 正常启动过，重置崩溃退避
    if [ "$ELAPSED" -gt "$STABLE_SECS" ]; then
        BACKOFF=3
    fi

    echo "[run-mc] ${BACKOFF}s 后重新拉起 MC ..." | tee -a /tmp/mc.log
    sleep "$BACKOFF"

    # 短命崩溃则逐步拉长等待
    if [ "$ELAPSED" -le "$STABLE_SECS" ] && [ "$BACKOFF" -lt "$MAX_BACKOFF" ]; then
        BACKOFF=$((BACKOFF + 3))
    fi
done
