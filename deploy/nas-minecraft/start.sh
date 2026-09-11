#!/bin/bash
# ============================================================================
#  Minecraft 服务器启动脚本  (FlamePaper / Paper 1.21.x)
#  放在服务器根目录，与 FlamePaper.jar 同级。
# ----------------------------------------------------------------------------
#  常用用法
#    ./start.sh                 自动分配内存（按当前可用内存推算，默认上限 6G）
#    ./start.sh 4G              指定堆大小（也接受 4096M / 4096）
#    ./start.sh --info          只打印内存现状与将要使用的参数，不启动
#    ./start.sh --help          显示帮助
#
#  进入服务器控制台
#    screen -r minecraft
#    （从控制台退出但不关服务器：先按 Ctrl+A，再按 D）
#
#  停止服务器（推荐做法，会正常保存世界）
#    进入控制台后输入：stop        然后回车
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
    sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
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

case "${1:-}" in
    -h|--help) usage; exit 0 ;;
    --info) DO_INFO=1 ;;
    "") DO_INFO=0 ;;
    *) DO_INFO=0 ;;
esac

AVAIL_MB="$(mem_available_mb)"

# 决定 XMX
if [ -n "${1:-}" ] && [ "$DO_INFO" -eq 0 ]; then
    XMX_MB="$(parse_size_to_mb "$1")"
    if [ -z "$XMX_MB" ] || [ "$XMX_MB" -lt 512 ]; then
        echo "✗ 无法识别的内存参数：$1"
        echo "  示例：./start.sh 4G   或   ./start.sh 4096"
        exit 1
    fi
    MEM_MODE="手动指定"
else
    # 自动：(可用 - 预留) * 比例%，再夹到 [下限, 上限]
    budget=$(( AVAIL_MB - RESERVE_MB ))
    [ "$budget" -lt 0 ] && budget=0
    auto=$(( budget * AUTO_RATIO_PCT / 100 ))
    [ "$auto" -gt "$AUTO_MAX_MB" ] && auto=$AUTO_MAX_MB
    [ "$auto" -lt "$AUTO_MIN_MB" ] && auto=$AUTO_MIN_MB
    # 万一连下限都超过可用内存，退到可用内存的一半，避免必然 OOM
    if [ "$AVAIL_MB" -gt 0 ] && [ "$auto" -gt "$AVAIL_MB" ]; then
        auto=$(( AVAIL_MB / 2 ))
        [ "$auto" -lt 512 ] && auto=512
    fi
    XMX_MB="$auto"
    MEM_MODE="自动（可用 ${AVAIL_MB}MB - 预留 ${RESERVE_MB}MB，取 ${AUTO_RATIO_PCT}%）"
fi

# 初始堆取上限的一半（不小于 1G）：让堆按需增长，不一次性占满
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
if ! command -v screen >/dev/null 2>&1 && [ -z "$SCREEN_BIN" ]; then
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

# ---------------------------- 重复启动保护 ---------------------------------
if "$SCREEN_BIN" -ls 2>/dev/null | grep -qE "[0-9]+\.${SESSION}\b"; then
    echo
    echo "⚠ 服务器已在运行（screen 会话：$SESSION）"
    echo "  进入控制台：screen -r $SESSION"
    echo "  如需停止：  进入控制台后输入 stop"
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
    -Dio.netty.native.workdir=/tmp
)

# 用 screen 的后台模式创建会话，会话名固定为 minecraft，
# 这样 `screen -r minecraft` 就能直接进入服务器控制台。
# 注意：变量展开放在 outer shell（双引号），确保参数与路径都正确传入。
"$SCREEN_BIN" -dmS "$SESSION" bash -c "cd \"$MC_DIR\" && exec \"$JAVA_BIN\" $(printf '%q ' "${JVM_FLAGS[@]}") -jar \"$JAR\" nogui"

sleep 3
if "$SCREEN_BIN" -ls 2>/dev/null | grep -qE "[0-9]+\.${SESSION}\b"; then
    echo
    echo "✓ 已启动。控制台会话：$SESSION"
    echo "  进入控制台：screen -r $SESSION"
else
    echo
    echo "✗ 启动失败（screen 会话未创建）。可查看日志："
    echo "  tail -n 50 \"$MC_DIR/logs/latest.log\""
    exit 1
fi
