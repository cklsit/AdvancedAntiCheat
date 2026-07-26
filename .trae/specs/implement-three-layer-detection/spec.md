# 全面反作弊核心自动检测系统 Spec

## Why
现有反作弊系统依赖简单规则和阈值判断，容易被高级作弊器绕过。需要构建三层引擎架构（实时规则、行为分析、长期关联）+ 融合中心，形成多层次、多引擎、自进化的智能防御体系。

## What Changes

### 第一层：实时规则与陷阱引擎 (毫秒级响应)
- **移动物理验证**：预测式物理模拟器，不可能动作检测，速度/加速度边界检测
- **战斗即时校验**：攻击距离/角度校验，交互频率限制，自瞄硬锁检测
- **蜜罐陷阱触发**：幻象矿石，幽灵实体，不可能的破坏进度
- **客户端与协议验证**：品牌指纹验证，非法包结构检测

### 第二层：行为特征分析引擎 (异步计算，概率输出)
- **玩家个人行为基线**：在线增量学习，个体异常Z检验
- **全局无监督异常检测**：孤立森林/LOF模型，新作弊聚类
- **多维专项检测**：
  - 战斗类：准星频谱分析，击退熵计算，多目标切换模式
  - 移动类：微时序Timer检测，时钟漂移监控
  - 背包类：状态机完整性验证，反应时间异常
  - 挖掘类：破坏曲线一致性，挖掘与移动协调性
- **操作生物特征识别**：按键动力学，鼠标运动学

### 第三层：长期关联与离线回溯引擎 (分钟级)
- **跨会话行为突变检测**：基线对比，二次验证触发
- **关联图谱与小号检测**：设备指纹关联，行为孪生匹配，社交图谱分析
- **离线规则回溯**：新规则历史扫描，时段异常统计
- **全球信誉查询**：跨服信誉数据库集成

### 融合评分与决策中心
- **动态贝叶斯加权融合**：条件依赖，历史先验，实时作弊概率(RCP)
- **决策动作阶梯**：
  - 0-50%: 正常放行
  - 50-75%: 增加检测频率
  - 75-95%: Captcha审判或物理扭曲
  - 95-99.5%: 自动临时封禁
  - >99.5%: 立即永久封禁
- **自适应学习闭环**：反馈收集，模型定期更新，规则自推荐

### 性能与鲁棒性保障
- 异步处理：独立线程池
- 内存缓存：热点数据缓存，定期异步写入
- 降级模式：自动降低计算频率
- 防绕过：基于服务端数据，不信任客户端

## Impact

### Affected Capabilities
- 移动作弊检测：90%+
- 战斗作弊检测：93%+
- 误报率：< 3%
- 漏报率：< 5%

### Affected Code
- `com.anticheat.detection.core` - 核心框架
- `com.anticheat.detection.physics` - 物理引擎
- `com.anticheat.detection.combat` - 战斗检测
- `com.anticheat.detection.movement` - 移动检测
- `com.anticheat.detection.mining` - 挖掘检测
- `com.anticheat.detection.inventory` - 背包检测
- `com.anticheat.detection.network` - 网络检测
- `com.anticheat.detection.timer` - Timer检测
- `com.anticheat.detection.bayesian` - 贝叶斯网络
- `com.anticheat.detection.fusion` - 融合引擎
- `com.anticheat.detection.association` - 关联检测
- `com.anticheat.managers.AdvancedDetectionManager` - 高级管理器

## ADDED Requirements

### Requirement: 三层引擎架构
系统 SHALL 实现三层检测引擎加融合中心的完整架构。

#### Scenario: 三层协同
- **WHEN** 玩家进行游戏行为
- **THEN** 第一层毫秒级响应，第二层异步概率计算，第三层分钟级关联分析，最终由融合中心综合决策

### Requirement: 实时物理验证
系统 SHALL 提供预测式物理模拟器，实时验证玩家移动。

#### Scenario: 位置偏差检测
- **WHEN** 玩家移动位置与物理模拟器预测位置持续偏差
- **THEN** 系统标记异常并计算误差向量

### Requirement: 蜜罐陷阱
系统 SHALL 实现幻象矿石、幽灵实体等蜜罐机制。

#### Scenario: 透视检测
- **WHEN** 玩家挖掘不存在的钻石矿
- **THEN** 系统立即判定为透视作弊

### Requirement: 行为基线学习
系统 SHALL 为每个玩家建立行为特征基线并持续学习。

#### Scenario: 个体异常检测
- **WHEN** 玩家行为偏离其个人基线超过3个标准差
- **THEN** 系统输出高作弊概率

### Requirement: 贝叶斯概率融合
系统 SHALL 实现动态贝叶斯网络，综合多模块输出。

#### Scenario: 多模块协同
- **WHEN** 移动、战斗、网络多个模块同时输出异常概率
- **THEN** 贝叶斯网络计算条件概率，输出综合RCP

### Requirement: 小号与团伙检测
系统 SHALL 通过数据库关联分析发现小号和作弊团伙。

#### Scenario: 团伙识别
- **WHEN** 多个玩家共享相同IP且行为特征高度相似
- **THEN** 系统识别为同一作弊团伙

### Requirement: 决策动作阶梯
系统 SHALL 根据RCP范围执行对应动作。

#### Scenario: 高风险玩家
- **WHEN** 玩家RCP > 95%
- **THEN** 系统执行自动临时封禁并通知管理员

## MODIFIED Requirements

### Requirement: 移动检测模块
现有速度/飞行检测 SHALL BE 重构为基于物理模拟的预测式检测。

### Requirement: 战斗检测模块
现有KillAura检测 SHALL BE 重构为基于频谱分析的多维度检测。

### Requirement: 风险评分系统
现有RiskHistory SHALL BE 扩展为基于贝叶斯网络的动态RCP机制。

## Performance Requirements

- TPS占用增加 < 2%
- 内存占用增加 < 100MB
- 第一层响应 < 1 tick
- 第二层计算 < 100ms
- 第三层分析 < 1 minute
