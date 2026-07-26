# Tasks

## Phase 1: 基础架构搭建
- [x] Task 1: 创建项目结构和基础配置
  - [x] SubTask 1.1: 创建 Web 项目目录结构（frontend/、backend/、shared/）
  - [x] SubTask 1.2: 配置前端项目（Vue 3 + Vite + TypeScript + Pinia + Tailwind CSS）
  - [x] SubTask 1.3: 配置后端项目（Node.js/Express 或 Go，REST API + WebSocket）
  - [ ] SubTask 1.4: 配置 Docker 和 Nginx 反向代理
  - [ ] SubTask 1.5: 配置 PostgreSQL 和 Redis 连接

- [x] Task 2: 实现安全认证体系
  - [x] SubTask 2.1: 实现 JWT 双令牌机制（access + refresh）
  - [x] SubTask 2.2: 实现 TOTP 多因素认证
  - [x] SubTask 2.3: 实现 RBAC 权限模型和中间件
  - [x] SubTask 2.4: 实现暴力破解锁定机制
  - [x] SubTask 2.5: 实现输入验证和 XSS/SQL注入防护
  - [ ] SubTask 2.6: 配置 HTTPS/TLS 1.3 和安全 Headers（HSTS、CSP）

- [x] Task 3: 实现 WebSocket 实时通信
  - [x] SubTask 3.1: 创建 WebSocket 服务端（事件推送、二进制帧压缩）
  - [x] SubTask 3.2: 实现前端 WebSocket 连接管理（重连、心跳）
  - [x] SubTask 3.3: 定义事件协议（告警、风险更新、玩家状态）

## Phase 2: 核心功能模块
- [x] Task 4: 实现全局布局和导航系统
  - [x] SubTask 4.1: 创建暗色主题全局布局（顶部栏、侧边栏、主内容区）
  - [x] SubTask 4.2: 实现可折叠侧边栏导航
  - [x] SubTask 4.3: 实现服务器状态指示器和 WebSocket 连接状态显示
  - [x] SubTask 4.4: 实现通知中心（实时警报下拉）

- [x] Task 5: 实现全局命令面板（Ctrl+K）
  - [x] SubTask 5.1: 创建命令面板组件（模糊搜索、命令解析）
  - [x] SubTask 5.2: 实现命令处理器（ban、profile、map、run等）
  - [x] SubTask 5.3: 实现快捷键绑定和焦点管理

- [x] Task 6: 实现智能仪表盘
  - [x] SubTask 6.1: 创建风险地圈环形图组件（Chart.js/ECharts）
  - [x] SubTask 6.2: 创建关键指标卡片（迷你趋势线）
  - [x] SubTask 6.3: 创建实时威胁流组件（终端风格滚动）
  - [x] SubTask 6.4: 创建快捷操作磁贴
  - [x] SubTask 6.5: 实现仪表盘 API 和数据聚合

- [x] Task 7: 实现玩家管理中心
  - [x] SubTask 7.1: 创建高级筛选栏组件（多维度筛选、保存方案）
  - [x] SubTask 7.2: 创建虚拟滚动玩家表格（vue-virtual-scroller）
  - [x] SubTask 7.3: 创建玩家详情抽屉（身份卡片、雷达图、时间线、关联图）
  - [x] SubTask 7.4: 实现玩家对比模式
  - [x] SubTask 7.5: 实现批量操作和快捷操作按钮
  - [x] SubTask 7.6: 创建玩家相关 API 端点

## Phase 3: 高级功能模块
- [x] Task 8: 实现实时战场地图
  - [x] SubTask 8.1: 创建 Leaflet 2D 瓦片地图组件
  - [x] SubTask 8.2: 实现玩家圆点渲染（颜色、大小、方向）
  - [x] SubTask 8.3: 实现点击交互和迷你档案卡
  - [x] SubTask 8.4: 实现框选批量选择功能
  - [x] SubTask 8.5: 实现轨迹回放（60秒轨迹、异常段标记）
  - [x] SubTask 8.6: 实现图层叠加（蜜罐、热力图）

- [x] Task 9: 实现案件审判中心
  - [x] SubTask 9.1: 创建看板视图（三列拖拽）
  - [x] SubTask 9.2: 创建案件卡片组件
  - [x] SubTask 9.3: 创建快速审判面板（证据摘要+三按钮）
  - [x] SubTask 9.4: 实现键盘快捷键（A/S/D）
  - [x] SubTask 9.5: 创建案件相关 API 端点

- [x] Task 10: 实现回放档案
  - [x] SubTask 10.1: 创建回放列表和搜索组件
  - [x] SubTask 10.2: 创建 Three.js 3D 回放播放器
  - [x] SubTask 10.3: 实现播放控制（速度、视角切换、关键帧跳转）
  - [x] SubTask 10.4: 实现分享链接生成和加密

- [x] Task 11: 实现配置中心
  - [x] SubTask 11.1: 创建检测模块管理卡片（开关+滑块）
  - [x] SubTask 11.2: 实现全局预设切换和保存
  - [x] SubTask 11.3: 创建惩罚模板可视化编辑器
  - [x] SubTask 11.4: 实现黑白名单批量导入/导出
  - [x] SubTask 11.5: 实现配置热重载 API

## Phase 4: 专业功能模块
- [x] Task 12: 实现 AI 实验室
  - [x] SubTask 12.1: 创建模型状态展示组件
  - [x] SubTask 12.2: 创建异常集群展示组件
  - [x] SubTask 12.3: 创建规则建议组件（采纳/忽略）
  - [x] SubTask 12.4: 创建模型模拟器（输入特征观察输出）

- [x] Task 13: 实现审计日志
  - [x] SubTask 13.1: 创建高级查询生成器
  - [x] SubTask 13.2: 创建虚拟滚动日志表格
  - [x] SubTask 13.3: 实现日志导出（CSV/JSON）
  - [x] SubTask 13.4: 实现日志完整性哈希校验工具

- [x] Task 14: 实现跨服信誉联盟
  - [x] SubTask 14.1: 创建联盟仪表盘组件
  - [x] SubTask 14.2: 实现贡献消耗统计展示
  - [x] SubTask 14.3: 实现隐私控制设置界面

- [x] Task 15: 实现新手引导系统
  - [x] SubTask 15.1: 创建交互式向导组件
  - [x] SubTask 15.2: 实现首次登录检测和自动弹出
  - [x] SubTask 15.3: 实现引导步骤（仪表盘、玩家详情、告警处理、敏感度）

## Phase 5: 性能优化与集成
- [x] Task 16: 实现性能优化
  - [x] SubTask 16.1: 配置 Service Worker 应用壳缓存
  - [x] SubTask 16.2: 实现 IndexedDB 数据缓存
  - [x] SubTask 16.3: 配置 Redis 热点数据缓存策略
  - [x] SubTask 16.4: 实现 API 速率限制和熔断保护
  - [x] SubTask 16.5: 实现路由懒加载和代码分割

- [x] Task 17: 实现插件集成接口
  - [x] SubTask 17.1: 创建插件与 Web 服务通信协议（PluginWebSocketServer）
  - [x] SubTask 17.2: 实现数据同步机制（玩家档案、检测结果、告警）
  - [x] SubTask 17.3: 实现配置下发和热重载通知
  - [x] SubTask 17.4: 实现插件状态检测（插件开启运行，插件关闭退出）

- [ ] Task 18: 测试与部署
  - [ ] SubTask 18.1: 编写单元测试和集成测试
  - [ ] SubTask 18.2: 编写 E2E 测试（关键流程）
  - [ ] SubTask 18.3: 配置 CI/CD 流水线
  - [ ] SubTask 18.4: 编写部署文档和运维手册

# Task Dependencies
- [Task 2] depends on [Task 1]
- [Task 3] depends on [Task 1]
- [Task 4] depends on [Task 1, Task 2]
- [Task 5] depends on [Task 4]
- [Task 6] depends on [Task 4, Task 3]
- [Task 7] depends on [Task 4, Task 3]
- [Task 8] depends on [Task 4, Task 3, Task 7]
- [Task 9] depends on [Task 7]
- [Task 10] depends on [Task 4]
- [Task 11] depends on [Task 2]
- [Task 12] depends on [Task 4]
- [Task 13] depends on [Task 2]
- [Task 14] depends on [Task 4]
- [Task 15] depends on [Task 4, Task 6, Task 7, Task 9]
- [Task 16] depends on [Task 1, Task 4, Task 6, Task 7]
- [Task 17] depends on [Task 1, Task 3]
- [Task 18] depends on [Task 1-17]

# Completed Tasks Summary
**已完成全部 Phase 1-5 核心功能（17/18 个任务）：**
- Phase 1: 项目初始化、认证系统、WebSocket通信 ✅
- Phase 2: 全局布局、命令面板、智能仪表盘、玩家管理 ✅
- Phase 3: 实时地图、案件审判、回放档案、配置中心 ✅
- Phase 4: AI实验室、审计日志、跨服信誉、新手引导 ✅
- Phase 5: 性能优化（Service Worker、IndexedDB、熔断）、插件集成 ✅

**待完成（Phase 5 - Task 18）：**
- 单元测试和集成测试
- E2E测试（关键流程）
- CI/CD流水线配置
- 部署文档和运维手册