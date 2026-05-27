# AdvancedAntiCheat

![Version](https://img.shields.io/badge/version-1.0.0-blue)
![License](https://img.shields.io/badge/license-MIT-green)
![Support](https://img.shields.io/badge/support-1.8.x%20--%201.21.x-orange)

一个适用于 Minecraft 1.8.x - 1.21.x 服务端的高级反作弊插件，支持自动封禁、玩家举报系统、客户端检查系统和数据库同步。

## 📋 前置依赖

本插件支持自动版本检测，可在多种服务器类型上运行：

| 服务器类型 | 最低版本 | 推荐版本 |
|-----------|---------|---------|
| Paper/Purpur | 1.19+ | 1.21.11+ |
| Spigot/Paper | 1.8.x | 1.8.8 / 1.21.11 |

> **说明**: 插件会自动检测服务器版本，选择合适的兼容模式运行。支持 1.8.x 和 1.21.x 之间的所有版本。

## ✨ 功能特性

### 🔍 反作弊检测
- **飞行检测** - 检测玩家异常飞行行为（支持创造模式排除）
- **速度检测** - 检测玩家移动速度异常
- 自动根据违规程度决定封禁时长

### 🔐 客户端检查系统
- `/checkclient <玩家> <QQ号>` - 开始客户端检查
- 被检查玩家将被：
  - 限制移动（无法移动、跳跃、飞行）
  - 限制交互（无法使用指令、聊天、攻击）
  - 施加失明效果
  - 显示自定义标题和聊天消息
- `/checkdone <玩家>` - 结束检查（通过）
- 检查超时自动永久封禁
- 查端过程中退出服务器自动永久封禁
- **可通过 checkclient.yml 自定义查端信息**

### ⚖️ 智能封禁系统
- 根据作弊严重程度自动封禁
- 支持临时封禁（1分钟 - 永久）
- **默认永久封禁**
- 可自定义封禁界面（通过 messages.yml）
- 封禁记录持久化存储

### 📢 玩家举报系统
- `/report <玩家> <原因>` - 普通玩家可举报作弊玩家
- **管理员实时收到带点击按钮的举报通知**
- **[前往举报者]** - 点击按钮一键传送到举报者身边
- 举报记录保存

### 🌐 跨服务器支持
- 支持 Velocity/BungeeCord 代理环境
- 数据库同步封禁信息
- 所有链接服务器自动禁止被封禁玩家进入

### 🗄️ 数据库支持
- 支持 SQLite（默认）、H2、MySQL、MongoDB、Redis
- **跨服务器封禁同步** - 在任一服务器封禁后，所有链接服务器均生效
- 数据库自动初始化

### 🎨 美化界面
- 使用 Minecraft 颜色代码美化所有消息
- **可自定义的封禁界面**
- **可自定义查端信息**（标题、聊天消息）
- 精美的举报通知（带点击按钮）

## 🚀 安装方法

1. 下载最新版本的插件 JAR 文件（[Release v1.0.0](https://github.com/cklsit/AntiCheat/releases/tag/v1.0.0)）
2. 将 JAR 文件放入服务器的 `plugins` 目录
3. 启动服务器，插件会自动生成配置文件
4. 根据需要修改配置文件：
   - `plugins/AdvancedAntiCheat/config.yml` - 主配置
   - `plugins/AdvancedAntiCheat/checkclient.yml` - 查端信息配置

## 📖 指令说明

### 玩家指令
| 指令 | 说明 | 权限 |
|------|------|------|
| `/report <玩家> <原因>` | 举报作弊玩家 | 无 |

### 管理员指令
| 指令 | 说明 | 权限 |
|------|------|------|
| `/goto <玩家>` | 传送至指定玩家（支持跨服务器） | `anticheat.goto` |
| `/ban <玩家> [时间] [原因]` | 封禁玩家（默认永久） | `anticheat.ban` |
| `/unban <玩家>` | 解封玩家 | `anticheat.unban` |
| `/checkclient <玩家> <QQ号>` | 开始客户端检查 | `anticheat.checkclient` |
| `/checkdone <玩家>` | 结束客户端检查（通过） | `anticheat.checkdone` |
| `/ac reload` | 重新加载配置 | `anticheat.admin` |
| `/ac stats` | 查看检测统计 | `anticheat.admin` |
| `/ac reports` | 查看待处理举报 | `anticheat.admin` |
| `/ac help` | 显示帮助信息 | `anticheat.admin` |
| `/ac` / `/anticheat` | 查看插件信息 | 无 |

## 🔐 权限节点

| 权限 | 说明 | 默认值 |
|------|------|--------|
| `anticheat.report` | 允许使用举报功能 | ✅ true |
| `anticheat.goto` | 允许传送至玩家 | 🔒 op |
| `anticheat.ban` | 允许封禁玩家 | 🔒 op |
| `anticheat.unban` | 允许解封玩家 | 🔒 op |
| `anticheat.checkclient` | 允许使用客户端检查 | 🔒 op |
| `anticheat.checkdone` | 允许结束客户端检查 | 🔒 op |
| `anticheat.admin` | 反作弊管理员权限 | 🔒 op |
| `anticheat.notify` | 接收举报通知 | 🔒 op |
| `anticheat.bypass.fly` | 绕过飞行检测 | 🔒 op |
| `anticheat.bypass.speed` | 绕过速度检测 | 🔒 op |

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

**可用变量**:
- `{vault_group}` - 管理员权限组
- `{admin}` - 执行检查的管理员名称
- `{qq}` - 管理员QQ号
- `{timeout}` - 超时时间（分钟）

## 📁 项目结构

```
AdvancedAntiCheat/
├── src/main/java/com/anticheat/
│   ├── AdvancedAntiCheat.java    # 主插件类
│   ├── commands/                 # 指令类
│   │   ├── AntiCheatCommand.java
│   │   ├── BanCommand.java
│   │   ├── CheckClientCommand.java
│   │   ├── CheckDoneCommand.java
│   │   ├── GotoCommand.java
│   │   ├── ReportCommand.java
│   │   └── UnbanCommand.java
│   ├── compat/                   # 版本兼容层
│   │   ├── ChatCompat.java
│   │   ├── ChatCompat1_8.java
│   │   ├── ChatCompat1_19.java
│   │   └── CompatManager.java
│   ├── detection/               # 检测模块
│   │   ├── Detection.java
│   │   ├── FlyDetection.java
│   │   └── SpeedDetection.java
│   ├── listeners/               # 事件监听器
│   │   ├── BungeeCordMessageListener.java
│   │   ├── PlayerCheckListener.java
│   │   ├── PlayerCommandListener.java
│   │   ├── PlayerJoinListener.java
│   │   ├── PlayerLoginListener.java
│   │   └── PlayerMoveListener.java
│   ├── managers/                 # 管理器
│   │   ├── BanManager.java
│   │   ├── CheckClientConfigManager.java
│   │   ├── CheckClientManager.java
│   │   ├── ConfigManager.java
│   │   ├── DatabaseManager.java
│   │   ├── DetectionManager.java
│   │   └── ReportManager.java
│   └── utils/                    # 工具类
│       └── VersionUtil.java
├── src/main/resources/
│   ├── config.yml               # 插件配置
│   ├── checkclient.yml          # 查端信息配置
│   └── messages.yml             # 消息配置
├── plugin.yml                    # 插件元数据
├── pom.xml                       # Maven构建配置
└── README.md                     # 项目说明
```

## 🛠️ 开发说明

### 环境要求
- Java 8+
- Maven 3.8+
- Spigot/Paper 1.8.x - 1.21.x

### 编译项目
```bash
mvn clean package
```

### 生成的文件
- `target/AdvancedAntiCheat-1.0.0.jar` - 可直接使用的插件文件

## 📄 许可证

本项目使用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

**保护您的服务器免受作弊侵害！** 🛡️

[![GitHub Release](https://img.shields.io/badge/GitHub-Release-blue?style=for-the-badge)](https://github.com/cklsit/AntiCheat/releases/tag/v1.0.0)
