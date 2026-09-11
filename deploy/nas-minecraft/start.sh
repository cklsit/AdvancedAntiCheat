#!/bin/bash
# ============================================================================
#  Minecraft 服务器启动脚本  (FlamePaper / Paper 1.21.x)
#  放在服务器根目录，与 FlamePaper.jar 同级。
# ----------------------------------------------------------------------------
#  常用用法
#    ./start.sh                 启动（自动分配内存，按可用内存推算，默认上限 6G）
#    ./start.sh 4G              启动并指定堆大小（也接受 4096M / 4096）
#    ./start.sh console         进入服务器控制台
#    ./start.sh stop            优雅停止服务器（等同控制台输入 stop）
#    ./start.sh status          查看运行状态 / 在线人数 / 内存
#    ./start.sh --info          只打印内存现状与将要使用的参数，不启动
#    ./start.sh --help          显示本帮助
#
#  进入服务器控制台（三种等价方式，推荐第一种）
#    ./start.sh console
#    screen -U -r minecraft
#    screen -r minecraft          （若你的客户端已是 UTF-8，不加 -U 也行）
#
#  ⚠ 退出控制台但不关服务器：按 Ctrl+A，松开后再按 D
#  ⚠ 千万不要在控制台按 Ctrl+C 或 Ctrl+D —— 那会触发服务器「优雅关闭」并保存世界退出！
#
#  停止服务器（推荐）
#    ./start.sh stop             或进入控制台后输入 stop 回车
#
#  中文乱码怎么办
#    本脚本已强制 UTF-8（设置 LANG/LC_ALL + screen -U），若仍乱码，
#    说明你本机的终端/SSH 客户端不是 UTF-8：
#      · Windows Terminal / PowerShell 7：默认 UTF-8，通常无需设置
#      · PuTTY   ：Window → Translation → Remote character set 选 UTF-8
#      · Xshell  ：会话属性 → 终端 → 编码 选 UTF-8
#      · SecureCRT：Session Options → Terminal → Appearance → Character encoding 选 UTF-8
#
#  为什么内存要自适应
#    这台 NAS 上同时还有 DSM 本体、其它套件与观察者容器。旧参数写死
#    -Xms10G -Xmx10G 会一启动就吃掉 10G，叠加观察者客户端后曾把整机压到
#    OOM 卡死。本脚本按「可用内存 - 保留量」推算，并设下限/上限，
#    避免把机器吃干。
# ============================================================================

set -euo pipefail

# DSM 的 screen 在 /opt/sbin（默认 PATH 里没有，sudo bash -c 下尤其找不到），
# 这里前置补上，保证本脚本与 screen 会话内的子进程都能找到所需命令。
export PATH="/opt/sbin:/usr/local/bin:/usr/bin:/bin:${PATH}"

# ---------------------------------------------------------------------------
# 编码：必须让 screen 以 UTF-8 工作，否则控制台里的中文会变成
# "ä¸Šçº¿" 这样的乱码（UTF-8 字节被当成单字节 Latin-1 渲染）。
# DSM 默认 LANG 为空 / locale 为 POSIX，所以这里显式挑一个可用的 UTF-8 locale。
# 可用 LANG=xxx ./start.sh 覆盖。
# ---------------------------------------------------------------------------
pick_utf8_locale() {
    local cand avail
    avail="$(locale -a 2>/dev/null || true)"
    for cand in zh_CN.utf8 zh_CN.UTF-8 en_US.utf8 en_US.UTF-8 C.utf8 C.UTF-8; do
        if printf '%s\n' "$avail" | grep -qix "$cand"; then
            printf '%s' "$cand"
            return 0
        fi
    done
    printf ''
}

if [ -z "${UTF8_LOCALE:-}" ]; then
    case "${LANG:-}${LC_ALL:-}" in
        *[Uu][Tt][Ff]*) : ;;                       # 已经是 UTF-8，尊重现有设置
        *) UTF8_LOCALE="$(pick_utf8_locale)" ;;
    esac
fi
if [ -n "${UTF8_LOCALE:-}" ]; then
    export LANG="$UTF8_LOCALE"
    export LC_ALL="$UTF8_LOCALE"
fi

# ---------------------------- 可调参数 --------------------------------------
MC_DIR="$(cd "$(dirname "$0")" && pwd)"   # 服务器根目录（= 本脚本所在目录）
SESSION="minecraft"                        # screen 会话名，勿改（否则 screen -r minecraft 失效）
JAR="FlamePaper.jar"
JAVA_BIN="${JAVA_BIN:-/volume1/jonson/wjx/mcjava21/bin/java}"
# screen 可执行文件：默认自动探测，也可用 SCREEN_BIN=/path/to/screen 覆盖
SCREEN_BIN="${SCREEN_BIN:-}"

# 自动模式：堆内存下限 / 上限（MB）
AUTO_MIN_MB="${AUTO_MIN_MB:-1024}"
AUTO_MAX_MB="${AUTO_MAX_MB:-6144}"
# 自动模式：给"系统 + 其它服务（含观察者容器）"预留的可用内存（MB）
RESERVE_MB="${RESERVE_MB:-3072}"
# 自动模式：可用内存去掉预留后，用于堆的百分比
AUTO_RATIO_PCT="${AUTO_RATIO_PCT:-70}"
# ---------------------------------------------------------------------------

usage() {
    sed -n '2,45p' "$0" | sed 's/^# \{0,1\}//'
}

MB_HUMAN() {  # 1024 -> 1G
    local mb="$1"
    if [ $((mb % 1024)) -eq 0 ]; then echo "$((mb / 1024))G"; else echo "${mb}M"; fi
}

mem_available_mb() {
    # 必须保证返回纯数字：读不到 /proc/meminfo 或没有 MemAvailable 行时返回 0
    local v
    v=$(awk '/^MemAvailable:/ {printf "%d", $2/1024; found=1} END {if (!found) printf "0"}' \
        /proc/meminfo 2>/dev/null)
    case "$v" in
        ''|*[!0-9]*) echo 0 ;;
        *) echo "$v" ;;
    esac
}

parse_size_to_mb() {  # 4G / 4096M / 4096 -> MB
    local v="$1"
    case "$v" in
        *[Gg]) echo $(( ${v%[Gg]} * 1024 )) ;;
        *[Mm]) echo "${v%[Mm]}" ;;
        *[0-9]) echo "$v" ;;
        *) echo "" ;;
    esac
}

# ---------------------------- 解析参数 -------------------------------------
SIZE_ARG=""
DO_INFO=0
MODE="start"

case "${1:-}" in
    -h|--help) usage; exit 0 ;;
    --info)    DO_INFO=1 ;;
    console|attach) MODE="console" ;;
    stop|shutdown)  MODE="stop" ;;
    status)         MODE="status" ;;
    "")             ;;
    *)              SIZE_ARG="$1" ;;
esac

# ---------------------------- screen 解析 ----------------------------------
if [ -z "$SCREEN_BIN" ]; then
    for c in /opt/sbin/screen /usr/local/bin/screen /usr/bin/screen /bin/screen; do
        if [ -x "$c" ]; then SCREEN_BIN="$c"; break; fi
    done
fi
if [ -z "$SCREEN_BIN" ]; then
    SCREEN_BIN="$(command -v screen 2>/dev/null || true)"
fi
if [ -z "$SCREEN_BIN" ]; then
    echo "✗ 未找到 screen 命令（DSM 上通常在 /opt/sbin/screen）"
    echo "  可用 SCREEN_BIN=/path/to/screen ./start.sh 覆盖"
    exit 1
fi

session_running() {
    "$SCREEN_BIN" -ls 2>/dev/null | grep -qE "[0-9]+\.${SESSION}\b"
}

# ---------------------------- 子命令：console / stop / status --------------
if [ "$MODE" = "console" ]; then
    if ! session_running; then
        echo "✗ 服务器未运行（找不到 screen 会话 $SESSION）"
        echo "  启动：cd \"$MC_DIR\" && ./start.sh"
        exit 1
    fi
    echo "进入控制台…  （退出但不关服：Ctrl+A 松开后按 D ；不要按 Ctrl+C）"
    echo "LANG=${LANG:-未设置}"
    exec "$SCREEN_BIN" -U -r "$SESSION"
fi

if [ "$MODE" = "stop" ]; then
    if ! session_running; then
        echo "服务器未在运行。"
        exit 0
    fi
    echo "发送 stop 指令，等待服务器保存世界并退出…"
    "$SCREEN_BIN" -S "$SESSION" -p 0 -X stuff "$(printf 'stop\r')"
    for _ in $(seq 1 60); do
        sleep 2
        if ! session_running; then
            echo "✓ 服务器已停止"
            exit 0
        fi
    done
    echo "⚠ 120s 内未退出，请用 ./start.sh console 进去查看"
    exit 1
fi

if [ "$MODE" = "status" ]; then
    if session_running; then
        echo "状态   : 运行中（screen 会话 $SESSION）"
        # 注意：DSM 自带的 pgrep 对 -f 支持不完整，直接匹配不到 java 进程；
        # 这里用 ps -ef 的第 8 列（命令）精确等于 java 路径来挑出真正的服务进程，
        # 避免误命中 screen 包装进程（它的命令行里也含 jar 名）。
        pid="$(ps -ef 2>/dev/null | awk -v jb="$JAVA_BIN" '$8 == jb {print $2; exit}')"
        if [ -n "$pid" ]; then
            echo "Java PID: $pid"
            grep -E "VmRSS" "/proc/$pid/status" 2>/dev/null | sed 's/^/内存   : /' || true
            tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null \
                | grep -oE '\-Xm[sx][0-9A-Za-z]+' | tr '\n' ' ' | sed 's/^/堆参数 : /' ; echo
        else
            echo "Java PID: (未匹配到，可用 ps -ef | grep java 手动查看)"
        fi
    else
        echo "状态   : 未运行"
    fi
    echo "LANG   : ${LANG:-未设置}"
    printf "可用内存: %s MB\n" "$(mem_available_mb)"
    exit 0
fi

# ---------------------------- 计算内存 -------------------------------------
AVAIL_MB="$(mem_available_mb)"

if [ -n "$SIZE_ARG" ] && [ "$DO_INFO" -eq 0 ]; then
    XMX_MB="$(parse_size_to_mb "$SIZE_ARG")"
    if [ -z "$XMX_MB" ] || [ "$XMX_MB" -lt 512 ]; then
        echo "✗ 无法识别的内存参数：$SIZE_ARG"
        echo "  示例：./start.sh 4G   或   ./start.sh 4096"
        exit 1
    fi
    MEM_MODE="手动指定"
else
    budget=$(( AVAIL_MB - RESERVE_MB ))
    [ "$budget" -lt 0 ] && budget=0
    auto=$(( budget * AUTO_RATIO_PCT / 100 ))
    [ "$auto" -gt "$AUTO_MAX_MB" ] && auto=$AUTO_MAX_MB
    [ "$auto" -lt "$AUTO_MIN_MB" ] && auto=$AUTO_MIN_MB
    if [ "$AVAIL_MB" -gt 0 ] && [ "$auto" -gt "$AVAIL_MB" ]; then
        auto=$(( AVAIL_MB / 2 ))
        [ "$auto" -lt 512 ] && auto=512
    fi
    XMX_MB="$auto"
    MEM_MODE="自动（可用 ${AVAIL_MB}MB - 预留 ${RESERVE_MB}MB，取 ${AUTO_RATIO_PCT}%）"
fi

XMS_MB=$(( XMX_MB / 2 ))
[ "$XMS_MB" -lt 1024 ] && XMS_MB=1024
[ "$XMS_MB" -gt "$XMX_MB" ] && XMS_MB="$XMX_MB"

# ---------------------------- 打印决策 -------------------------------------
echo "=============================================="
echo " Minecraft 服务器启动"
echo "----------------------------------------------"
echo " 服务器目录 : $MC_DIR"
echo " 服务端 jar  : $JAR"
echo " Java       : $JAVA_BIN"
printf " 物理可用内存: %s MB\n" "$AVAIL_MB"
if [ "$AVAIL_MB" -le 0 ]; then
    echo " ⚠ 未能从 /proc/meminfo 读到 MemAvailable，已退化为按下限启动"
fi
echo " 内存模式   : $MEM_MODE"
echo " 堆参数     : -Xms$(MB_HUMAN "$XMS_MB") -Xmx$(MB_HUMAN "$XMX_MB")"
echo " 终端编码   : LANG=${LANG:-未设置}"
if [ -z "${LANG:-}" ]; then
    echo " ⚠ 未找到可用的 UTF-8 locale，控制台中文可能乱码"
fi
echo "=============================================="

if [ "$DO_INFO" -eq 1 ]; then
    echo "（--info 模式，未启动服务器）"
    exit 0
fi

# ---------------------------- 前置检查 -------------------------------------
if [ ! -f "$MC_DIR/$JAR" ]; then
    echo "✗ 找不到服务端 jar：$MC_DIR/$JAR"
    exit 1
fi
if [ ! -x "$JAVA_BIN" ]; then
    echo "✗ 找不到可执行的 Java：$JAVA_BIN"
    echo "  可用 JAVA_BIN=/path/to/java ./start.sh 覆盖"
    exit 1
fi

# ---------------------------- 重复启动保护 ---------------------------------
if session_running; then
    echo
    echo "⚠ 服务器已在运行（screen 会话：$SESSION）"
    echo "  进入控制台：./start.sh console   或  screen -U -r $SESSION"
    echo "  优雅停止：  ./start.sh stop"
    exit 0
fi

# ---------------------------- 启动 -----------------------------------------
# JVM 参数说明：
#   G1GC + 下面这组 Aikar 参数是 Paper 官方社区推荐的通用配置，
#   在"内存不宽裕、玩家数中等"的场景下停顿与内存占用都比较稳。
#   刻意不加 -XX:+AlwaysPreTouch：它会在启动时把整个堆立刻提交，
#   在共享内存的 NAS 上没有必要，反而容易把机器顶到 OOM。
JVM_FLAGS=(
    -Xms"${XMS_MB}M" -Xmx"${XMX_MB}M"
    -XX:+UseG1GC
    -XX:+ParallelRefProcEnabled
    -XX:MaxGCPauseMillis=200
    -XX:+UnlockExperimentalVMOptions
    -XX:+DisableExplicitGC
    -XX:G1NewSizePercent=30
    -XX:G1MaxNewSizePercent=40
    -XX:G1HeapRegionSize=8M
    -XX:G1ReservePercent=20
    -XX:G1HeapWastePercent=5
    -XX:G1MixedGCCountTarget=4
    -XX:InitiatingHeapOccupancyPercent=15
    -XX:G1MixedGCLiveThresholdPercent=90
    -XX:G1RSetUpdatingPauseTimePercent=5
    -XX:SurvivorRatio=32
    -XX:MaxTenuringThreshold=1
    -XX:+PerfDisableSharedMem
    -Dusing.aikars.flags=https://mcflags.emc.gs
    -Daikars.new.flags=true
    -Dfile.encoding=UTF-8
    -Dsun.stdout.encoding=UTF-8
    -Dsun.stderr.encoding=UTF-8
    -Dio.netty.native.workdir=/tmp
)

# 用 screen 的后台模式创建会话，会话名固定为 minecraft，
# 这样 `screen -r minecraft` / `./start.sh console` 就能进入服务器控制台。
# -U 让 screen 以 UTF-8 处理字符，配合上面的 LANG/LC_ALL 解决控制台中文乱码。
"$SCREEN_BIN" -U -dmS "$SESSION" bash -c "cd \"$MC_DIR\" && exec \"$JAVA_BIN\" $(printf '%q ' "${JVM_FLAGS[@]}") -jar \"$JAR\" nogui"

sleep 3
if session_running; then
    echo
    echo "✓ 已启动。控制台会话：$SESSION"
    echo "  进入控制台：./start.sh console   （退出不关服：Ctrl+A 然后 D）"
    echo "  优雅停止：  ./start.sh stop"
else
    echo
    echo "✗ 启动失败（screen 会话未创建）。可查看日志："
    echo "  tail -n 50 \"$MC_DIR/logs/latest.log\""
    exit 1
fi
