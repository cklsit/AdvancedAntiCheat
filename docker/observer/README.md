# 观察者客户端（Minecraft 1.8.8 + Xvfb + observerctl HTTP API）

本目录打包了一个 **Minecraft 1.8.8 离线模式"观察者"客户端** Docker 镜像，并通过一个极简 Flask HTTP API（`observerctl.py`）在容器内控制 `ffmpeg x11grab` 录制 Xvfb 虚拟屏幕为 HLS，并在停止时合成 mp4 录像文件。

默认 `docker-compose.yml` 一次启动 3 个这样的容器（`ReplayObserver_1 / 2 / 3`），对应 Bukkit 插件端的 3 个观察位。

---

## 1. 前置条件

- 宿主机：**Synology NAS / Linux x86_64** 或任何能跑 Docker 的 amd64 机器
- **Docker 24+** 和 **docker compose v2**（或用 `docker-compose` 1.29+）
- 至少 **6GB 空闲内存**（3 个容器 × 1.5G = 4.5G，再加系统缓冲）
- 宿主机磁盘：每个 observer 录制 1 小时大约占用 1~3GB（HLS + mp4，取决于 CRF）
- 一台可达的 **Minecraft 1.8.8 Bukkit / Spigot / Paper 服务器**，并已部署本项目 AntiCheat 插件，包含观察者 teleport/spectate 管理功能

## 2. 准备 1.8.8 客户端 jar

⚠️ 由于 Docker 构建时无法保证 Mojang 下载 URL 可访问，我们**要求用户手动放置**客户端 jar：

官方下载（SHA1 校验值）：

```
URL   = https://launcher.mojang.com/v1/objects/c0b0a0be0a1e6e35a78a0e77ed6d1de6a42a7a6e/client.jar
SHA1  = c0b0a0be0a1e6e35a78a0e77ed6d1de6a42a7a6e
```

在宿主机上执行：

```bash
cd docker/observer
curl -L -o client.jar \
  "https://launcher.mojang.com/v1/objects/c0b0a0be0a1e6e35a78a0e77ed6d1de6a42a7a6e/client.jar"
# Linux 校验：
sha1sum client.jar
# macOS 校验：
shasum -a 1 client.jar
# Windows PowerShell 校验：
Get-FileHash client.jar -Algorithm SHA1
```

确认输出 == `c0b0a0be0a1e6e35a78a0e77ed6d1de6a42a7a6e`。**不一致请立刻删除，不要构建镜像。**

## 3. 修改 `docker-compose.yml` 中的服务器地址

打开 `docker-compose.yml`，3 个服务内分别有：

```yaml
- MC_SERVER_HOST=192.168.1.132
- MC_SERVER_PORT=25565
```

把它们改成**你的游戏服务器实际 IP/端口**：

- 若 MC 服务器跑在同宿主机的 Docker bridge 网络上：改用该容器名 / 自定义内网域名
- 若 MC 服务器跑在同宿主机 host 网络：Linux 宿主机可写 `172.17.0.1`（Docker 网桥网关）或使用 `extra_hosts`；macOS/Windows Docker Desktop 可写 `host.docker.internal`
- 若 MC 服务器是另一台局域网/公网机器：写对应 IP

并确保防火墙 / 安全组允许 3 个 observer 容器访问该 IP:PORT。

## 4. 白名单与账号准备

1. 打开 MC 服务器 `server.properties`，把 `online-mode=false`（客户端以 offline 方式加入，
   账号 UUID 写死在 compose 里，不经过正版校验）
2. 把以下 3 个账号加入白名单（如果服务器开启了 `white-list=true`）：

   ```
   /whitelist add ReplayObserver_1
   /whitelist add ReplayObserver_2
   /whitelist add ReplayObserver_3
   ```

3. 确保它们不会被插件踢出（例如 `AntiCheat` 的观察者白名单权限 `anticheat.observer`）

## 5. 构建镜像

```bash
cd docker/observer
docker compose build
```

⚠️ **首次构建比较慢（20~30 分钟常见）**：

- apt 要下载安装 openjdk-17、ffmpeg、mesa、xvfb 等
- 下载 LWJGL 2.9.4 压缩包 (~30MB) 并解压
- 若 SourceForge 下载失败，会 fallback 到 GitHub release 镜像

若 `docker compose build` 报错 **COPY failed: file not found: client.jar not found** —— 你漏做了步骤 2。

## 6. 启动 3 个 observer 容器

```bash
docker compose up -d
```

查看：

```bash
docker ps -a
# 健康检查需要 start_period=120s 之后才开始计数
docker compose logs -f replay-observer-1
```

## 7. 首次启动：VNC 手动连接服务器（**必须**）

由于 Minecraft 1.8.8 客户端的 `Main` 类**不支持** `--server <host>` 命令行参数，进入主菜单后，首次需要**人工**操作一次以写入 `servers.dat`，之后客户端重启会自动尝试连接最近服务器。

操作方法：

1. **编辑 `docker-compose.yml`**：
   - 目标 observer（先从 1 号开始即可）的 `VNC_ENABLE=false` → `VNC_ENABLE=true`
   - 同时解开对应的 `ports:` 注释：
     ```yaml
     ports:
       - "5910:5900"   # observer-1
       # - "5911:5900" # observer-2
       # - "5912:5900" # observer-3
     ```
2. 重启容器应用新环境变量：`docker compose up -d --force-recreate replay-observer-1`
3. 本机装一个 VNC 客户端：RealVNC / TigerVNC / TightVNC 任意都可
4. 连接地址：`127.0.0.1:5910`（x11vnc 已开启 `-nopw`，不需要密码；**不要对外网暴露 5910 端口**）
5. 在 MC 主菜单里：
   - 点 **Multiplayer** → **Direct Connect**（或 Add Server）
   - Server Address 填 **MC_SERVER_HOST:MC_SERVER_PORT**（即 compose 里写的那对 IP/端口）
   - 点 **Join Server**，看到进入游戏并登录为 `ReplayObserver_1`
6. 可选：按 `Esc` → 把客户端 Options 里的 **Render Distance** 调到 8 或更高、关闭 Sounds（录制无声）
7. 退出 VNC 客户端，服务器会继续在后台运行（`servers.dat` 已写入 `/mc`，即容器镜像层；若希望持久化可把 `/mc/servers.dat` 挂到宿主机，目前设计是容器保留该层即可）

对 observer-2、3 重复以上操作（分别用端口 5911/5912）。**三个都做完一次后**，可以把 `VNC_ENABLE=false` 和 `ports:` 再注释掉，避免不必要的攻击面。

## 8. 健康检查 & 日志验证

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

理想状态：3 个容器都是 **(healthy)**。

```bash
# API 是否启动：
docker exec replay-observer-1 curl -sS http://127.0.0.1:8080/status
# 期望看到类似：
# {"mc_status":"running","xvfb":"ok","ffmpeg_pid":null,"observer_id":"1"}

# 观察 MC 运行日志：
docker exec replay-observer-1 tail -n 50 -f /tmp/mc.log
```

## 9. 外部插件调用 observerctl API

Bukkit 插件（或 Web 面板）调用示例（以 observer-1 为目标）：

```bash
# 开始录制一段回放，uuid 为玩家的 violations 会话号
curl -X POST "http://<宿主机>:18081/start?uuid=case-42-a1b2c3"
# → {"ok":true,"ffmpeg_pid":12345,...}

# 结束并合成 mp4
curl -X POST "http://<宿主机>:18081/stop?uuid=case-42-a1b2c3"
# → {"ok":true,"mp4":"/hls/case-42-a1b2c3.mp4","uuid":"case-42-a1b2c3"}
```

⚠️ compose 默认**没有把 8080 映射到宿主机**。如果 AntiCheat 插件在外部宿主机上而不是容器里，请在 compose 中为每个 observer 增加端口映射：

```yaml
ports:
  - "18081:8080"   # replay-observer-1  → 宿主机 18081
  # - "18082:8080" # replay-observer-2
  # - "18083:8080" # replay-observer-3
```

或者把 Bukkit 容器放入同一 Docker 网络，直接以 `http://replay-observer-1:8080` 互相通信。

---

## 常见问题 FAQ

### Q1：容器启动就 exited (127)，docker logs 里看不到有用信息？

**原因 90% 是 `client.jar` 没放**，Dockerfile 的 `COPY client.jar /mc/client.jar` 会在 build 阶段直接失败，
不会构建成功。如果你是 build 成功但运行 exit 127，那是容器里某个命令找不到：

```bash
docker logs replay-observer-1 | tail -n 40
```

- 看到 `java: command not found`：openjdk 安装失败，重新 `docker compose build --no-cache`
- 看到 `ffmpeg: command not found` / `xvfb-run: command not found`：同上，重新构建
- 看到 `/mc/entrypoint.sh: No such file or directory`：检查你的 git config 是否把 shell 脚本自动转成了 CRLF。用 `file entrypoint.sh` 查看是否 `with CRLF line terminators`；若是，执行：
  ```bash
  dos2unix entrypoint.sh run-mc.sh healthcheck.sh observerctl.py
  ```

### Q2：启动后 VNC 里看到 MC 画面黑屏 / 闪退？

1. 看 MC 日志：`docker exec replay-observer-1 tail -n 100 /tmp/mc.log`
2. 典型报错：
   - `org.lwjgl.LWJGLException: X Error of failed request: BadValue` →
     一般是 GLX 扩展 / Mesa 库问题。确保宿主机上没有强制挂载 `/dev/dri`（纯 CPU 软渲染走 mesa llvmpipe 是 OK 的）。
   - `java.lang.OutOfMemoryError` → 把容器 `mem_limit` 调到 2g，或 run-mc.sh 里 `-Xmx1G` 降到 `-Xmx768M`
   - 停在 `Downloading 1.8.8.json` / 下载资源：首次需要联网，超时 300s 后会重跑，最多 3 次。如果宿主机离线请先代理或手工拷贝 assets 到 `/mc/assets/`。
3. 确认 display 本身：`docker exec replay-observer-1 xdpyinfo -display :99 | head -n 10`，必须正常输出。

### Q3：`/start` 成功但 ffmpeg 录到的 mp4 是全黑 / 0 字节？

1. 先 `/status` 看返回的 `xvfb` 是不是 `ok`
2. 容器里跑：
   ```bash
   docker exec replay-observer-1 xwininfo -display :99 -root | grep 'Width\|Height'
   ```
   应该看到 1280×720。不是的话 Xvfb 启动参数被覆盖了（DISPLAY / -screen 不对）。
3. 手工跑一下 ffmpeg 看 stderr：
   ```bash
   docker exec -it replay-observer-1 ffmpeg -y -f x11grab -video_size 1280x720 \
     -framerate 30 -i :99 -frames:v 10 /tmp/test.mp4
   ```
   如果报 `x11grab` 格式不存在 → ffmpeg 编译没带 x11grab，需要 `apt-get install` 版本里的 `ffmpeg` 支持；debian bookworm 默认二进制 **是支持的**。

### Q4：MC 客户端能启动但无法连接服务器？

1. 确认服务器 `server.properties` 的 `online-mode=false`，白名单已加。
2. 从容器里网络联调：
   ```bash
   docker exec replay-observer-1 ping -c 2 192.168.1.132   # 你的 MC_SERVER_HOST
   docker exec replay-observer-1 bash -c 'echo -e "\x00" > /dev/tcp/192.168.1.132/25565 && echo tcp_ok'
   ```
   - ping 不通：bridge 网络路由不通，改 `MC_SERVER_HOST` 或把 compose 的 `network_mode: host`（仅 Linux）
   - tcp 不通：MC 服务器没开，或者宿主机防火墙 / cloud provider 安全组
3. 若服务器是 `online-mode=true` 且你无法关闭（正版服）：需要一个外置的 bot/proxy 给这 3 个 UUID 签合法 accessToken，超出本 README 范围。
