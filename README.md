# AdvancedAntiCheat

一个适用于 Minecraft 1.21.x 服务端的高级反作弊插件，支持自动封禁、玩家举报系统和数据库同步。

## 📋 前置依赖

本插件基于 Paper 1.21.11 开发，使用 Adventure API 原生支持彩色文本和点击按钮：

| 服务器类型 | 最低版本 | 推荐版本 |
|-----------|---------|---------|
| Paper | 1.19+ | 1.21.11+ |
| Purpur | 1.19+ | 1.21.11+ |

> **说明**: 使用 Adventure API 原生实现，无需反射，支持所有兼容 Paper 的服务端。
> 举报系统中的点击按钮功能会在不支持的环境下降级为普通文本消息。

## ✨ 功能特性

### 🔍 反作弊检测
- **飞行检测** - 检测玩家异常飞行行为
- **速度检测** - 检测玩家移动速度异常（含创造模式排除）
- **透视检测** - 检测玩家透视作弊行为
- **杀戮光环** - 检测自动攻击作弊
- **攻击距离** - 检测攻击距离作弊

### ⚖️ 智能封禁系统
- 根据作弊严重程度自动封禁（1分钟 - 1天）
- 支持临时封禁和永久封禁
- **可自定义封禁界面**（通过 messages.yml）
- 封禁记录持久化存储

### 📢 玩家举报系统
- `/report <玩家> <原因>` - 普通玩家可举报作弊玩家
- **管理员实时收到带点击按钮的举报通知**
- **[前往举报者] [前往作弊者]** - 点击按钮一键传送
- 举报记录保存

### 🔧 管理员指令
- `/goto <玩家>` - 传送至指定玩家身边（支持跨服务器）
- `/ban <玩家> [时间] [原因]` - 手动封禁玩家
- `/unban <玩家>` - 解封玩家
- `/ac help/stats/reload/reports` - 插件管理

### 🗄️ 数据库支持
- 支持 SQLite（默认）、H2、MySQL、MongoDB、Redis
- **跨服务器封禁同步** - 在任一服务器封禁后，所有链接服务器均生效
- 数据库自动初始化

### 🎨 美化界面
- 使用 Minecraft 颜色代码美化所有消息
- **可自定义的封禁界面**
- **可自定义所有命令消息**
- 精美的举报通知（带点击按钮）
- 详细的帮助信息展示

## 🚀 安装方法

1. 下载最新版本的插件 JAR 文件
2. 将 JAR 文件放入服务器的 `plugins` 目录
3. 启动服务器，插件会自动生成配置文件
4. 根据需要修改配置文件：
   - `plugins/AdvancedAntiCheat/config.yml` - 检测配置
   - `plugins/AdvancedAntiCheat/messages.yml` - 消息文本配置

## 📖 指令说明

### 玩家指令
| 指令 | 说明 | 权限 |
|------|------|------|
| `/report <玩家> <原因>` | 举报作弊玩家 | `anticheat.report` |

### 管理员指令
| 指令 | 说明 | 权限 |
|------|------|------|
| `/goto <玩家>` | 传送至指定玩家 | `anticheat.goto` |
| `/ban <玩家> [时间] [原因]` | 封禁玩家 | `anticheat.ban` |
| `/unban <玩家>` | 解封玩家 | `anticheat.unban` |
| `/ac reload` | 重新加载配置 | `anticheat.admin` |
| `/ac stats` | 查看检测统计 | `anticheat.admin` |
| `/ac reports` | 查看待处理举报 | `anticheat.admin` |
| `/ac help` | 显示帮助信息 | `anticheat.admin` |

## 🔐 权限节点

| 权限 | 说明 | 默认值 |
|------|------|--------|
| `anticheat.report` | 允许使用举报功能 | ✅ true |
| `anticheat.goto` | 允许传送至玩家 | 🔒 op |
| `anticheat.ban` | 允许封禁玩家 | 🔒 op |
| `anticheat.unban` | 允许解封玩家 | 🔒 op |
| `anticheat.admin` | 反作弊管理员权限 | 🔒 op |
| `anticheat.notify` | 接收举报通知 | 🔒 op |
| `anticheat.bypass` | 绕过所有检测 | 🔒 op |

## ⏱️ 封禁时长配置

| 作弊类型 | 默认封禁时长 |
|----------|--------------|
| 飞行作弊 | 1小时 |
| 速度作弊 | 30分钟 |
| 透视作弊 | 6小时 |
| 杀戮光环 | 1天 |
| 攻击距离 | 2小时 |

## 🗄️ 数据库配置

在 `config.yml` 中配置数据库连接：

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
  redis:
    host: "localhost"
    port: 6379
    password: ""
  mongodb:
    host: "localhost"
    port: 27017
    database: "anticheat"
```

## 📝 自定义消息配置

在 `messages.yml` 中可以自定义所有显示给玩家的消息：

```yaml
# 封禁界面
ban-screen:
  lines:
    - "§c§l═══════════════════════════════════════"
    - "§c              §l⚠ 已被服务器封禁 ⚠"
    - "§c§l═══════════════════════════════════════"
    - ""
    - "§7封禁原因: §f{reason}"
    - ""
    - "§7封禁时长: {banTime}"
    - ""
    - "§c§l═══════════════════════════════════════"
    - "§6如有疑问请联系服务器管理员"
    - "§c§l═══════════════════════════════════════"

# 命令消息
commands:
  report-success: "§a举报已提交！管理员将尽快处理。"
  ban-success: "§a玩家 §e{player} §a已被封禁 §e{banTime}"
```

**可用变量**:
- `{player}` - 玩家名称
- `{reason}` - 封禁/举报原因
- `{bannedBy}` - 封禁执行者
- `{banTime}` - 封禁时长
- `{reporter}` - 举报者
- `{target}` - 被举报者

## 📁 项目结构

```
AdvancedAntiCheat/
├── src/main/java/com/anticheat/
│   ├── AdvancedAntiCheat.java    # 主插件类
│   ├── commands/                 # 指令类
│   │   ├── AntiCheatCommand.java
│   │   ├── BanCommand.java
│   │   ├── GotoCommand.java
│   │   ├── ReportCommand.java
│   │   └── UnbanCommand.java
│   ├── detection/               # 检测模块
│   │   ├── Detection.java
│   │   ├── FlyDetection.java
│   │   ├── SpeedDetection.java
│   │   ├── EspDetection.java
│   │   ├── KillAuraDetection.java
│   │   └── ReachDetection.java
│   ├── listeners/               # 事件监听器
│   │   ├── BungeeCordMessageListener.java
│   │   ├── PlayerCommandListener.java
│   │   ├── PlayerJoinListener.java
│   │   ├── PlayerLoginListener.java
│   │   └── PlayerMoveListener.java
│   └── managers/                 # 管理器
│       ├── BanManager.java
│       ├── ConfigManager.java
│       ├── DatabaseManager.java
│       ├── DetectionManager.java
│       └── ReportManager.java
├── src/main/resources/
│   ├── config.yml               # 插件配置
│   └── messages.yml              # 消息配置
├── plugin.yml                    # 插件元数据
├── pom.xml                       # Maven构建配置
└── README.md                     # 项目说明
```

## 🛠️ 开发说明

### 环境要求
- Java 21+
- Maven 3.8+
- Paper/Purpur 1.21.x

### 编译项目
```bash
mvn clean package
```

### 生成的文件
- `target/AdvancedAntiCheat-1.0.0.jar` - 可直接使用的插件文件

## 📄 许可证

本项目使用 MIT 许可证。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

**保护您的服务器免受作弊侵害！** 🛡️
