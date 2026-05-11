# AdvancedAntiCheat v1.0.0

## 插件介绍

AdvancedAntiCheat 是一款适用于 Minecraft 1.8.x - 1.21.x 服务端的反作弊插件，支持多种数据库（SQLite、H2、MySQL、Redis、MongoDB）进行跨服务器封禁同步。

## 主要功能

### 🛡️ 自动检测与封禁
- 速度作弊检测
- 飞行作弊检测
- 自动根据违规程度决定封禁时间

### 📢 举报系统
- 玩家可使用 `/report <玩家> <原因>` 举报作弊玩家
- 管理员可收到带点击按钮的举报通知
- 点击按钮可一键传送到举报者身边

### 🔍 客户端检查系统
- 管理员可使用 `/checkclient <玩家> <QQ号>` 开始客户端检查
- 被检查玩家将被：
  - 限制移动（无法移动、跳跃、飞行）
  - 限制交互（无法使用指令、聊天、攻击）
  - 施加失明效果
  - 显示自定义标题和聊天消息
- 管理员可使用 `/checkdone <玩家>` 结束检查
- 检查超时自动永久封禁
- 查端过程中退出服务器自动永久封禁

### 🌐 跨服务器支持
- 支持 Velocity/BungeeCord 代理环境
- 数据库同步封禁信息
- 所有链接服务器自动禁止被封禁玩家进入

### ⚙️ 自定义配置
- 查端信息可通过 `checkclient.yml` 自定义
- 数据库配置可通过 `config.yml` 自定义
- 支持多种数据库类型

## 指令列表

| 指令 | 权限 | 说明 |
|------|------|------|
| `/report <玩家> <原因>` | 无 | 举报玩家 |
| `/goto <玩家>` | `anticheat.goto` | 传送到玩家身边 |
| `/ban <玩家> [时间] [原因]` | `anticheat.ban` | 封禁玩家 |
| `/unban <玩家>` | `anticheat.unban` | 解封玩家 |
| `/checkclient <玩家> <QQ号>` | `anticheat.checkclient` | 开始客户端检查 |
| `/checkdone <玩家>` | `anticheat.checkdone` | 结束客户端检查 |
| `/anticheat` 或 `/ac` | 无 | 查看插件信息 |

## 权限节点

| 权限节点 | 说明 | 默认 |
|----------|------|------|
| `anticheat.bypass.fly` | 绕过飞行检测 | OP |
| `anticheat.bypass.speed` | 绕过速度检测 | OP |
| `anticheat.ban` | 使用封禁指令 | OP |
| `anticheat.unban` | 使用解封指令 | OP |
| `anticheat.goto` | 使用传送指令 | OP |
| `anticheat.checkclient` | 使用客户端检查指令 | OP |
| `anticheat.checkdone` | 结束客户端检查指令 | OP |
| `anticheat.admin` | 所有管理员权限 | OP |

## 数据库支持

- **SQLite** - 内置，无需配置
- **H2** - 内置，无需配置
- **MySQL** - 需要额外配置
- **Redis** - 需要额外配置
- **MongoDB** - 需要额外配置

## 安装说明

1. 下载 `AdvancedAntiCheat-1.0.0.jar`
2. 将其放入服务器的 `plugins` 文件夹
3. 重启服务器
4. 根据需要编辑 `plugins/AdvancedAntiCheat/config.yml` 和 `plugins/AdvancedAntiCheat/checkclient.yml`

## 注意事项

- 本插件需要 Java 8 或更高版本运行
- 客户端检查功能需要服务器支持反射操作
- 跨服务器传送需要 BungeeCord/Velocity 代理环境

## 许可证

MIT License

---

**构建时间**: 2026-05-11
**插件版本**: 1.0.0
**支持服务器**: Minecraft 1.8.x - 1.21.x
