# AdvancedAntiCheat

一个适用于 Minecraft 1.21.x 服务端的高级反作弊插件，支持自动封禁和玩家举报系统。

## ✨ 功能特性

### 🔍 反作弊检测
- **飞行检测** - 检测玩家异常飞行行为
- **速度检测** - 检测玩家移动速度异常
- **透视检测** - 检测玩家透视作弊行为
- **杀戮光环** - 检测自动攻击作弊
- **攻击距离** - 检测攻击距离作弊

### ⚖️ 智能封禁系统
- 根据作弊严重程度自动封禁（1分钟 - 1天）
- 支持临时封禁和永久封禁
- 美观的封禁界面
- 封禁记录持久化存储

### 📢 玩家举报系统
- `/report <玩家> <原因>` - 普通玩家可举报作弊玩家
- 管理员实时收到举报通知
- 举报记录保存

### 🔧 管理员指令
- `/goto <玩家>` - 传送至指定玩家身边
- `/ban <玩家> [时间] [原因]` - 手动封禁玩家
- `/unban <玩家>` - 解封玩家
- `/ac help/stats/reload/reports` - 插件管理

### 🎨 美化界面
- 使用 Minecraft 颜色代码美化所有消息
- 精美的封禁界面
- 详细的帮助信息展示

## 🚀 安装方法

1. 下载最新版本的插件 JAR 文件
2. 将 JAR 文件放入服务器的 `plugins` 目录
3. 启动服务器，插件会自动生成配置文件
4. 根据需要修改 `plugins/AdvancedAntiCheat/config.yml`

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

## 📁 项目结构

```
AdvancedAntiCheat/
├── src/main/java/com/anticheat/
│   ├── AdvancedAntiCheat.java    # 主插件类
│   ├── commands/                 # 指令类
│   ├── detection/                # 检测模块
│   ├── listeners/                # 事件监听器
│   └── managers/                 # 管理器
├── plugin.yml                    # 插件配置
├── pom.xml                       # Maven配置
└── README.md                     # 项目说明
```

## 🛠️ 开发说明

### 环境要求
- Java 21+
- Maven 3.8+
- Spigot/Paper 1.21.x

### 编译项目
```bash
mvn clean package
```

### 生成的文件
- `target/AdvancedAntiCheat-1.0.0.jar` - 可直接使用的插件文件

## 📝 配置文件示例

```yaml
detection:
  fly:
    enabled: true
    maxViolations: 5
    banTime: "1h"
  speed:
    enabled: true
    maxViolations: 5
    banTime: "30m"
  esp:
    enabled: true
    maxViolations: 3
    banTime: "6h"
  killaura:
    enabled: true
    maxViolations: 5
    banTime: "1d"
  reach:
    enabled: true
    maxViolations: 5
    banTime: "2h"

messages:
  prefix: "[AntiCheat]"
  noPermission: "§c您没有权限执行此命令！"
  reportSuccess: "§a举报已提交！管理员将尽快处理。"
```

## 📄 许可证

本项目使用 MIT 许可证，详见 LICENSE 文件。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

**保护您的服务器免受作弊侵害！** 🛡️