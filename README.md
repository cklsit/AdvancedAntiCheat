# AdvancedAntiCheat

![Version](https://img.shields.io/badge/version-2.1.0-blue)
![License](https://img.shields.io/badge/license-MIT-green)
![Support](https://img.shields.io/badge/support-1.8.x%20--%201.21.x-orange)
![Java](https://img.shields.io/badge/Java-21-red?logo=openjdk)

适用于 **Minecraft 1.8.x – 1.21.x** 服务端（Paper / Purpur / Spigot / FlamePaper）的高级反作弊插件。

不只是阈值检测——AAC 构建了「**多层检测引擎 → 玩家画像 → 贝叶斯概率融合 → 五级智能处置 → Web 可视化取证**」的完整反作弊闭环：

- 🧠 **RCP 实时作弊概率**：多模块概率经贝叶斯网络融合，自适应学习调整权重，输出 NORMAL → MONITOR → CAPTCHA → TEMP_BAN → PERM_BAN 五级处置
- 🕵️ **八大类 40+ 检测项**：移动 / 战斗 / 挖掘建筑 / 背包物品 / 网络协议 / 客户端指纹 / 蜜罐陷阱 / 行为分析全项覆盖
- 👤 **玩家画像系统**：瞄准分析、挖矿模式、背包状态机、击键动力学、身份指纹、社交关联图谱与风险历史
- 🔐 **三套人工介入机制**：查端（客户端核实）、验证码（专用世界任务）、漏洞赏金（白盒自测沙箱）
- 🌐 **跨服务器同步封禁**：SQLite / H2 / MySQL / MongoDB / Redis，配合 BungeeCord / Velocity 全服生效
- 🖥️ **内嵌 Web 管理面板**：Vue 3 SPA + REST + WebSocket，总览 / 玩家 / 案件 / 配置 / 审计 / AI 实验室 / 联盟图谱 / 实时地图
- 🎬 **违规实时回放取证**：Docker 观察者客户端跟随 suspect，ffmpeg 抓屏 LL-HLS 直播 + 20Hz 遥测合成 HUD 叠层，一键归档取证包

## 📋 前置依赖

插件启动时自动检测服务器版本，在 1.8.x 与 1.19+ API 之间选择兼容模式运行：

| 服务器类型 | 最低版本 | 推荐版本 |
|-----------|---------|---------|
| Paper/Purpur | 1.19+ | 1.21.11+ |
| Spigot/Paper | 1.8.x | 1.8.8 / 1.21.11 |

| 软依赖 | 说明 |
|--------|------|
| BungeeCord / Velocity | 跨服务器消息通道，用于跨服封禁同步与 `/goto` |
| ProtocolLib | 协议级检测（非法数据包结构、微时序、假方块）；未安装时相关检测自动降级，插件照常运行 |

## ✨ 功能特性

### 🔍 检测体系

**单项经典检测**（违规计数达阈值自动处置）：

| 检测项 | 说明 |
|--------|------|
| Fly | 飞行检测（支持创造模式排除） |
| Speed | 移动速度异常 |
| KillAura | 杀戮光环 |
| Reach | 攻击距离异常（NORMAL/MAX/ABSOLUTE 三级校验） |
| ESP | 透视 / 实体追踪 |
| FastBreak | 破坏方块速度异常 |
| Scaffold | 自动搭桥 |
| NoSlow | 使用物品未减速 |

**高级模块化检测**（八大分类，键名均为 `config.yml` 中 `detection.*` 配置项）：

| 分类 | 检测项 |
|------|--------|
| 一、移动类 | `timer`、`water_walk`、`high_jump`、`no_fall`、`spider`、`phase`、`jesus`、`clock_drift`（时钟漂移）、空中二段跳 / 不可能变向（`ImpossibleActionDetector`） |
| 二、战斗类 | `auto_hit`、`cps_anomaly`、`no_knockback`、`auto_totem`、`aim_angle`、`aimbot_spectrum`、`knockback_entropy`、`anti_knockback`、`auto_armor`、`auto_potion`；Aimbot 硬锁定识别（`AimbotHardLockDetector`）、CPS 限制器 |
| 三、挖掘与建筑 | `fast_break`、`auto_miner`（矿机模式，默认永久封）、`break_consistency`、`mining_coord`、`illegal_place`、`no_slow_mining` |
| 四、背包与物品 | `auto_stack`、`container_spam`、`item_move_spam`、`offhand_swap`、`inventory_dupe`（疑似复制，默认永久封） |
| 五、网络与协议 | `protocol_spoof`、`brand_spoof`、`malformed_packet`（需 ProtocolLib 增强） |
| 六、客户端指纹 | `gui_fingerprint`（GUI 响应延迟指纹）、`render_distance`（渲染距离验证矿透）、隐写特征物品追踪小号流转 |
| 七、蜜罐陷阱 | `x_ray`（幻象诱饵矿石）、`chest_esp`、`player_radar`、`tracer`、`fake_drop`、`fake_escape`（假逃脱重定向沙箱收集情报） |
| 八、行为分析 | `behavior_anomaly`（个人基线偏离）、`keystroke`（击键动力学）、`anti_recon`（反侦察）、`global_anomaly`（孤立森林全局异常） |

另有独立 **物理模拟复算**（`PhysicsSimulator`，服务端重放客户端运动学验证位移合法性）与 **关联检测**（小号识别、团队作弊、设备指纹、社交图谱、行为相似度）。

### 🧠 概率融合与智能决策

- `ProbabilityFusionEngine` + `BayesianNetwork` 融合各模块概率证据
- `RCPComputer` 计算玩家实时作弊概率（RCP），叠加先验、网络延迟与趋势分析
- `AdaptiveLearningSystem` 基于历史数据自适应调整各检测模块权重
- `DecisionActionCenter` 按 RCP 阈值输出五级处置；同类提示自动冷却防刷屏
- `PerformanceMonitor` 监控 TPS/CPU/内存，压力过大自动进入降级模式降低检测频次

### 👤 玩家画像（profiles）

每位玩家维护长期档案：移动/战斗/挖矿/背包/社交五大行为特征、瞄准平滑度分析、挖矿时间规律、背包状态机、操作节奏（`TimerDetection`）、身份指纹（历史 ID / IP / 客户端版本 / 语言 / 硬件）、账号关联图与风险历史（每小时自动衰减），为决策中心提供长程上下文，并可在 Web 面板与游戏内 GUI（`/ac profile`）查看。

### 🔐 查端系统（客户端核实）

- `/checkclient <玩家> <QQ号>` - 开始客户端检查
- 被检查玩家将被：限制移动（无法移动、跳跃、飞行）、限制交互（无法使用指令、聊天、攻击）、施加失明效果、显示自定义标题和聊天消息
- `/checkdone <玩家>` - 结束检查（通过）
- 检查超时自动永久封禁；查端过程中退出服务器自动永久封禁
- **可通过 checkclient.yml 自定义查端信息**（标题 / 聊天文案 / 超时）

### 🧩 验证码系统

- `/captcha <玩家|toggle|timelimit>` - 对玩家发起验证码测试；支持新玩家自动验证码
- 玩家被传送进**专用验证码世界**（自定义生成器），按提示完成指定交互任务（如 `TypeA_DirectInteraction`）
- 由融合决策触发（CAPTCHA 级）或管理员手动发起，是介于「监控」与「封禁」之间的低误伤处置手段

### 💰 漏洞赏金系统

- `/bounty enter` - 进入漏洞赏金沙箱
- `/bounty leave` - 离开漏洞赏金沙箱
- `/bounty start <任务>` - 开始赏金任务
- `/bounty report <描述>` - 报告发现的漏洞
- `/bounty lb` - 查看赏金排行榜

**支持的任务类型**:

| 任务类型 | 描述 | 时间限制 |
|----------|------|----------|
| `MOVE_BASIC` | 基础移动测试（从A点到B点） | 3分钟 |
| `MOVE_ADVANCED` | 高级移动测试（空中直角变向） | 5分钟 |
| `COMBAT_BASIC` | 基础战斗测试（击杀僵尸） | 5分钟 |
| `COMBAT_ADVANCED` | 高级战斗测试（杀戮光环检测） | 5分钟 |
| `INVENTORY_CHALLENGE` | 物品栏挑战（快速切换物品） | 3分钟 |
| `FREE_TEST` | 自由测试（给予所有道具和怪物） | 10分钟 |

**任务自动评估**:
- **DETECTED** - 检测到作弊行为
- **BYPASSED** - 无检测且无可疑行为（绕过成功）
- **ZERO_DAY** - 无检测但有可疑行为（高危发现）

### ⚖️ 智能封禁系统

- 根据作弊严重程度自动封禁（临时 1 分钟 ~ 永久），违规级别支持踢出阈值与人工审核升级阈值
- **默认永久封禁**，封禁界面可通过 `messages.yml` 自定义
- 封禁记录持久化存储，审计全留痕

### 📢 玩家举报系统

- `/report <玩家> <原因>` - 普通玩家可举报作弊玩家
- **管理员实时收到带点击按钮的举报通知**，[前往举报者] 一键传送
- 举报记录保存，Web 面板案件中心可审理裁决

### 🌐 跨服务器支持

- 支持 Velocity / BungeeCord 代理环境
- 数据库同步封禁信息，任一服务器封禁后所有链接服务器自动拒绝进入
- `/goto <玩家>` 支持跨服传送

### 🖥️ 内嵌 Web 管理面板

插件自带 Javalin HTTP + WebSocket 服务器，启动后浏览器直接访问 `http://<服务器IP>:8080/`：

| 页面 | 功能 |
|------|------|
| 总览 Dashboard | 检测统计、模块状态、服务器状态实时曲线 |
| 玩家管理 | 列表/详情/画像/封禁操作 |
| 案件中心 | 违规案件审理与裁决（RBAC 分工） |
| 系统配置 | 检测项开关与阈值热更新 |
| 审计日志 | 全部 Web 操作留痕查询 |
| AI 实验室 | IsolationForest / 在线 K-Means 异常分析可视化 |
| 联盟图谱 | 关联账号社交图谱可视化 |
| 实时地图 | Canvas 2D 玩家位置沙盘 |
| 违规回放 | 观察者直播观看 + 遥测 HUD 叠层 + 归档下载 |

- 内置 **RBAC**：admin / moderator / reviewer / observer 四角色演示账号（明文密码 = 用户名）
- 生产部署：游戏内执行 `/ac genpwd <新密码>` 生成 bcrypt 哈希替换 `config.yml` 中 `web.auth.accounts[].password-hash`，再 `/ac reload` 生效

### 🎬 违规回放（Observer 取证子系统）

针对「截图录屏难以还原作弊现场」的痛点，AAC 提供服务端侧的**真实客户端回放直播**：

```
玩家进入 → SurveillanceScheduler 排队调度 → Docker 观察者客户端按需进服
  → setSpectatorTarget 相机绑定（attach 眼位 / shoulder 过肩，违规自动切过肩）
  → 容器 Xvfb + ffmpeg 抓屏 → LL-HLS 推流 → Web 面板 hls.js 播放
  → 20Hz 遥测（坐标/血量/准星目标/36 格背包）WS 下发 → 前端 HUD 叠层对齐视频
  → 会话结束 / 玩家退出 → 视频 + 遥测统一 ZIP 归档（默认保留 7 天）
```

- **观察者集群自动部署**：插件首次启动检测 Docker，可用则自动 build + up 观察者容器；不可用时打印「安装 Docker 或关闭回放功能」二选一引导（`/ac replay setup|disable`）
- **按需进服**：默认仅当有人在面板观看了才让观察者登录服务器，无人观看 120 秒自动退服，资源占用极低
- **取证画质锁定**：记录到违规即锁分辨率/码率下限，清晰度优先于延迟
- 群晖 DSM（Synology）NAS 部署已适配，见 `deploy/nas-minecraft/start.sh`

配置项集中在 `config.yml` 的 `replay.*`（调度并发/队列、机位、HLS、遥测频率、归档保留、观察者实例列表）。

## 🚀 安装方法

1. 下载最新版本的插件 JAR 文件（[Releases](https://github.com/cklsit/AntiCheat/releases)，Nightly 每日 21:00 北京时间自动发布）
2. 将 JAR 文件放入服务器的 `plugins` 目录
3. 启动服务器，插件会自动生成配置文件：
   - `plugins/AdvancedAntiCheat/config.yml` - 主配置
   - `plugins/AdvancedAntiCheat/checkclient.yml` - 查端信息配置
   - `plugins/AdvancedAntiCheat/messages.yml` - 消息配置
4. 按需修改配置后执行 `/ac reload`
5. （可选）启用违规回放：宿主机安装 Docker，重启插件自动部署观察者集群；详见上文「违规回放」

## 📖 指令说明

### 玩家指令

| 指令 | 说明 | 权限 |
|------|------|------|
| `/report <玩家> <原因>` | 举报作弊玩家 | `anticheat.report`（默认开放） |
| `/bounty ...` | 漏洞赏金计划 | `anticheat.bounty`（默认开放） |
| `/ac` / `/anticheat` | 查看插件信息 | 无 |

### 管理员指令

| 指令 | 说明 | 权限 |
|------|------|------|
| `/ban <玩家> [时间] [原因]` | 封禁玩家（默认永久，跨服同步） | `anticheat.ban` |
| `/unban <玩家>` | 解封玩家 | `anticheat.unban` |
| `/goto <玩家>` | 传送至指定玩家（支持跨服务器） | `anticheat.goto` |
| `/checkclient <玩家> <QQ号>` | 开始客户端检查 | `anticheat.checkclient` |
| `/checkdone <玩家>` | 结束客户端检查（通过） | `anticheat.checkclient` |
| `/captcha <玩家\|toggle\|timelimit>` | 验证码测试 | `anticheat.captcha` |
| `/bounty enter\|leave\|invite\|report\|lb\|start\|complete` | 赏金沙箱管理 | `anticheat.bounty` / `.bounty.admin` |
| `/ac reload` | 重新加载配置 | `anticheat.admin` |
| `/ac stats` | 查看检测统计 | `anticheat.admin` |
| `/ac reports` | 查看待处理举报 | `anticheat.admin` |
| `/ac profile <玩家>` | 查看玩家档案 GUI | `anticheat.admin` |
| `/ac genpwd <密码>` | 生成 Web 账号 bcrypt 哈希 | `anticheat.admin` |
| `/ac help` | 列出全部命令 | `anticheat.admin` |
| `/aac_replay_follow <观察者> <目标>` | 观察者跟随指定玩家 | `anticheat.replay.control` |
| `/aac_replay_unfollow <观察者>` | 停止跟随 | `anticheat.replay.control` |

## 🔐 权限节点

| 权限 | 说明 | 默认值 |
|------|------|--------|
| `anticheat.report` | 使用举报功能 | ✅ true |
| `anticheat.bounty` | 使用漏洞赏金 | ✅ true |
| `anticheat.bounty.admin` | 赏金计划管理员 | 🔒 op |
| `anticheat.bounty.unlimited` | 无限制沙箱时间 | 🔒 op |
| `anticheat.goto` | 传送至玩家 | 🔒 op |
| `anticheat.ban` / `anticheat.unban` | 封禁 / 解封 | 🔒 op |
| `anticheat.checkclient` | 客户端检查 | 🔒 op |
| `anticheat.captcha` | 验证码功能 | 🔒 op |
| `anticheat.admin` | 反作弊管理员 | 🔒 op |
| `anticheat.notify` | 接收举报通知 | 🔒 op |
| `anticheat.replay.observer` | 观察者账号（免踢/免拦截） | ❌ false |
| `anticheat.replay.control` | 控制观察者跟随 | 🔒 op |
| `anticheat.bypass.fly` / `anticheat.bypass.speed` | 绕过对应检测 | 🔒 op |

## 🗄️ 数据库配置

在 `config.yml` 中配置数据库连接（跨服部署时所有节点使用同一后端即可自动同步封禁）：

```yaml
database:
  type: "sqlite"  # 支持: sqlite, h2, mysql, mongodb, redis
  server-name: "Server-1"
  sqlite:
    path: "anticheat.db"
  mysql:
    host: "localhost"
    port: 3306
    database: "anticheat"
    username: "root"
    password: ""
```

Web 面板操作与审计日志同样持久化到该数据库。

## 📝 自定义查端配置

在 `checkclient.yml` 中可以自定义查端时显示的信息：

```yaml
checkclient:
  title: "§c您正在被管理员查端!"
  subtitle: "§e请看聊天框继续下一步"

  chat_message:
    - "§8§m------------------------------------------------"
    - "§f您已被 §b{vault_group} §f成员 §c§l冻结所有操作."
    - "§f请在 §b{timeout} §f分钟内添加 §c{admin} §f的 §bQQ §f好友 §a{qq} §f进行客户端核实。"
    - "§f请不要退出此房间或关闭游戏,否则您的账号将会被封禁！"
    - "§8§m------------------------------------------------"

  timeout_minutes: 60
```

**可用变量**: `{vault_group}`（管理员权限组）、`{admin}`（管理员名称）、`{qq}`（管理员QQ号）、`{timeout}`（超时分钟）

## 📁 项目结构

```
AdvancedAntiCheat/
├── src/main/java/com/anticheat/
│   ├── AdvancedAntiCheat.java         # 主插件类（生命周期编排）
│   ├── detection/                     # 检测系统
│   │   ├── core/                      #   模块抽象基座（DetectionModule/DetectionResult/Evidence）
│   │   ├── fusion/                    #   概率融合与决策（贝叶斯/RCP/五级处置）
│   │   ├── movement|combat|physics|association|network|
│   │   ├── behavior|fingerprint|inventory|mining|timer/   # 各专项检测
│   ├── profiles/                      # 玩家画像（行为追踪/瞄准/矿机/背包状态机/指纹）
│   ├── managers/                      # 业务管理器（封禁/举报/查端/检测编排/审计/回放）
│   │   ├── replay/                    #   回放调度、观察者集群部署、遥测环、归档
│   │   └── ffmpeg/                    #   容器 ffmpeg HTTP 控制
│   ├── replay/                        # 回放相机绑定、准星探针、监视调度
│   ├── ai/                            # IsolationForest / 在线 K-Means（AI 实验室）
│   ├── captcha/ | bounty/             # 验证码世界 / 漏洞赏金沙箱
│   ├── commands/ | listeners/ | gui/  # 11 命令、13 监听器、档案 GUI
│   ├── web/                           # Javalin REST + WebSocket 后端（RBAC/审计/回放推流）
│   ├── repositories/                  # SQL(SQLite/H2/MySQL) / Mongo / Redis 数据访问层
│   ├── compat/ | utils/ | integration/# 1.8↔1.21 兼容层 / 工具 / ProtocolLib 钩子
├── src/main/resources/                # config.yml / checkclient.yml / messages.yml
├── web-panel/                         # Vue 3 + Vite + Pinia + TailwindCSS 前端
├── docker/observer/                   # 回放观察者容器（headless MC + Xvfb + ffmpeg）
├── deploy/nas-minecraft/              # 群晖 DSM 部署脚本
├── .github/workflows/                 # CI：paper+spigot 矩阵构建 + nightly release
├── plugin.yml                         # 插件元数据、命令、权限
└── pom.xml                            # Maven 构建（集成前端构建 + Shade 重定位）
```

> 更详细的架构文档见仓库根目录 [CODE_WIKI.md](CODE_WIKI.md)。

## 🛠️ 开发说明

### 环境要求
- **JDK 21+**（paper-api 1.21.11 要求）
- Maven 3.8+
- Node.js 无需预装（`frontend-maven-plugin` 构建时自动安装 v20.11.0）

### 编译项目
```bash
mvn clean package              # 默认 -Ppaper（Paper 1.21.11 API），完整构建含前端
mvn clean package -Pspigot     # Spigot 1.8.8 API 变体
mvn clean package '-DskipFrontend=true'   # 跳过前端构建（复用已有 dist）
```

构建产物为 `target/AdvancedAntiCheat-2.1.0.jar`（fat-jar，第三方依赖已重定位到 `com.anticheat.libs.*`，内含 Web 面板静态资源），可直接放入服务端 `plugins/`。

### 前端开发
```bash
cd web-panel
npm install
npm run dev      # 开发服务器
npm run build    # 产物至 web-panel/dist，随后由 Maven 打包进 JAR
```

## 📄 许可证

本项目使用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

**保护您的服务器免受作弊侵害！** 🛡️
