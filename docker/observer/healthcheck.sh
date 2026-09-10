#!/bin/bash
set -euo pipefail

# Docker HEALTHCHECK 脚本：容器内执行
# 1. observerctl HTTP API /status 能返回 200
# 2. Xvfb 显示 :99 可查询（xdpyinfo 成功）
# 两个任一失败 → 容器 unhealthy。

curl -fsS http://127.0.0.1:8080/status >/dev/null || exit 1

xdpyinfo -display "${DISPLAY:-:99}" >/dev/null 2>&1 || exit 1

exit 0
