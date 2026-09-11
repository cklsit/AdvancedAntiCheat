#!/bin/bash
set -euo pipefail

# ===== Minecraft 1.8.8 客户端启动包装器（按需进服 + 断线自愈）=====
#
# 设计目标：观察者客户端**不再开机常驻服务器**。只有当管理员通过网页面板
# 观看某玩家直播时，才让客户端进入服务器；最后一名观看者离开后过一段时间
# 自动退服。这样 NAS 上常态下的占用就只有 Xorg + observerctl（很轻），
# 而不是一个 720p 软件渲染的完整 MC 客户端。
#
# 进服/退服由 observerctl 的 /mc/up 与 /mc/down 控制，落地方式是标志文件：
#   MC_ONDEMAND=true（默认）
#     - 容器启动时清除标志 → 客户端不启动，容器保持 healthy
#     - 标志出现 → 拉起 MC，--server 自动连服
#     - 标志消失 → 终止 MC，客户端退服
#   MC_ONDEMAND=false
#     - 保持旧的常驻行为（开机即进服），适合观察者专机
#
# 其它行为保持不变：
#   - 崩溃自动重启 + 退避（3s 起、上限 30s；存活 >60s 重置退避）
#   - 断线自愈看门狗：客户端断线后只会退回主菜单且进程不退出，--server 也不会
#     重试，会导致服务器重启/重载后 observer 永久停在主菜单（表现为"登录不上"）。
#     看门狗通过内核 TCP 表检测连接状态，必要时重启 MC 进程以重新连接。
#   - 显示焦点保持器：容器内无窗口管理器，X 焦点默认是 PointerRoot，MC 窗口收不到
#     FocusIn 会被判为失焦 —— 既会自动弹出 Game menu（暂停菜单挡住录制画面），
#     也会让键盘事件送不进去。这里持续把输入焦点钉在 MC 窗口上，并关闭
#     pauseOnLostFocus 作为双保险。
#   - 1.8.8 Main 类支持 --server/--port：启动即自动连服，跳过主菜单 GUI。
#   - 窗口尺寸固定为 1280x720（与 Xorg dummy 屏幕一致），避免四周黑边。
#   - JVM 输出同时写入 /tmp/mc.log，便于排查。

cd /mc

CLASSPATH="/mc/client.jar:/mc/lib/*"
NATIVE_DIR="/mc/lib/natives"
MAIN_CLASS="net.minecraft.client.main.Main"

# 环境变量校验（缺失立即退出）
: "${USERNAME:?USERNAME env is required}"
: "${UUID:?UUID env is required}"
: "${MC_SERVER_HOST:?MC_SERVER_HOST env is required}"
: "${MC_SERVER_PORT:?MC_SERVER_PORT env is required}"

MC_ONDEMAND="${MC_ONDEMAND:-true}"
WANT_FLAG="${MC_ONDEMAND_FLAG:-/tmp/mc_wanted}"

# 客户端窗口尺寸：与 Xorg dummy 屏幕一致（1280x720），这样 x11grab 抓整屏时
# 画面能填满，不会像用 MC 默认 854x480 那样四周留大片黑边。
MC_WINDOW_W="${MC_WINDOW_W:-1280}"
MC_WINDOW_H="${MC_WINDOW_H:-720}"

STABLE_SECS=60
BACKOFF=3
MAX_BACKOFF=30
IDLE_POLL_SECS=2

mkdir -p /mc/assets
touch /tmp/mc.log

# 关掉「失去焦点即暂停」：与 display_keeper 形成双保险。
# 即使某次瞬间失焦，客户端也不会弹出 Game menu 遮挡录制画面。
ensure_options() {
    local opt=/mc/options.txt
    if [ ! -f "$opt" ]; then
        printf 'pauseOnLostFocus:false\n' > "$opt"
        return
    fi
    if grep -q '^pauseOnLostFocus:' "$opt"; then
        sed -i 's/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' "$opt"
    else
        printf 'pauseOnLostFocus:false\n' >> "$opt"
    fi
}
ensure_options
echo "[run-mc] options.txt: $(grep -h '^pauseOnLostFocus:' /mc/options.txt 2>/dev/null || echo '未设置')" \
    | tee -a /tmp/mc.log

# 按需模式下容器启动时先清掉标志：避免上次异常退出残留标志导致"开机即进服"
if [ "$MC_ONDEMAND" = "true" ]; then
    rm -f "$WANT_FLAG"
fi

# 当前是否被要求进服
want_mc() {
    if [ "$MC_ONDEMAND" != "true" ]; then
        return 0
    fi
    [ -f "$WANT_FLAG" ]
}

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

        # 空闲状态不判定（主循环负责，不在此处拉起或杀进程）
        if ! want_mc; then
            miss=0; last_pid=""; proc_start=0
            continue
        fi

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

# ------------------------------------------------------------------
# 显示输入焦点保持器（关键：否则直播画面会停在 MC 暂停菜单上）
# ------------------------------------------------------------------
# 容器内**没有窗口管理器**，X 的默认焦点策略是 PointerRoot —— 意味着 MC 窗口
# 永远收不到 FocusIn 事件。由此产生两个后果：
#   1) MC 认为窗口处于失焦状态 → 自动弹出 "Game menu"（暂停菜单），
#      录像画面被菜单整个挡住（表现就是"一直停在暂停界面"）；
#   2) 键盘事件送不到客户端（xdotool key 看似执行成功但客户端毫无反应）。
# 解决方式：持续用 XSetInputFocus 把输入焦点钉在 MC 窗口上。
# 必须在启动 java **之前**就开跑，这样客户端一建窗就拿到焦点，
# 从根上避免弹出暂停菜单。
display_keeper() {
    local d="${DISPLAY:-:99}" wid cur
    while true; do
        sleep 2
        wid=$(DISPLAY="$d" xdotool search --name "Minecraft" 2>/dev/null | tail -n1 || true)
        [ -z "$wid" ] && continue
        cur=$(DISPLAY="$d" xdotool getwindowfocus 2>/dev/null || echo 0)
        if [ "$cur" != "$wid" ]; then
            DISPLAY="$d" xdotool windowfocus "$wid" 2>/dev/null || true
        fi
    done
}

display_keeper &
KEEPER_PID=$!
echo "[run-mc] 显示焦点保持器已启动 (pid=${KEEPER_PID})" | tee -a /tmp/mc.log

echo "[run-mc] 模式：$( [ "$MC_ONDEMAND" = "true" ] && echo '按需进服（等待 /mc/up 指令）' || echo '常驻进服' )，标志文件 ${WANT_FLAG}" \
    | tee -a /tmp/mc.log

while true; do
    # ---------- 空闲：不进服，低频轮询等待指令 ----------
    if ! want_mc; then
        if pgrep -f "$MAIN_CLASS" >/dev/null 2>&1; then
            echo "[run-mc] $(date '+%F %T') 收到退服指令，终止 MC 客户端" | tee -a /tmp/mc.log
            pkill -f "$MAIN_CLASS" >/dev/null 2>&1 || true
            sleep 3
        fi
        BACKOFF=3
        sleep "$IDLE_POLL_SECS"
        continue
    fi

    # ---------- 进服：拉起 MC ----------
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
        --width "$MC_WINDOW_W" \
        --height "$MC_WINDOW_H" \
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

    # 若期间已被要求退服，直接回到空闲分支，不做重启
    if ! want_mc; then
        echo "[run-mc] 已处于空闲状态，等待 /mc/up 指令" | tee -a /tmp/mc.log
        BACKOFF=3
        continue
    fi

    echo "[run-mc] ${BACKOFF}s 后重新拉起 MC ..." | tee -a /tmp/mc.log
    sleep "$BACKOFF"

    # 短命崩溃则逐步拉长等待
    if [ "$ELAPSED" -le "$STABLE_SECS" ] && [ "$BACKOFF" -lt "$MAX_BACKOFF" ]; then
        BACKOFF=$((BACKOFF + 3))
    fi
done
