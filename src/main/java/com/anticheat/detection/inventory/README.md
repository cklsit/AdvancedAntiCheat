# 背包状态机检测系统

## 概述
先进的背包状态机检测系统，用于检测游戏中各种背包作弊行为，包括自动图腾交换、瞬时交换、非法状态跳跃等。

## 文件列表

### 1. InventoryDetectionModule.java
**背包检测模块主类**
- 继承 `BaseDetectionModule`
- 集成状态机验证和AutoTotem检测
- 核心方法：
  - `check(Player)` - 执行检测
  - `validateStateMachine(UUID)` - 验证状态机
  - `detectAutoTotem(UUID)` - 检测AutoTotem

### 2. InventoryStateMachine.java
**背包状态机**
- 管理状态转换和历史记录
- 定义合法状态和转换规则
- 核心方法：
  - `validateTransition()` - 验证转换合法性
  - `addTransition()` - 添加转换记录
  - `getAllowedTransitions()` - 获取允许的转换

**状态类型：**
- NO_INVENTORY - 无界面
- PLAYER_INVENTORY - 玩家背包
- CHEST_INVENTORY - 箱子
- ENDER_CHEST - 末影箱
- ANVIL - 铁砧
- VILLAGER_TRADE - 村民交易
- OFF_HAND - 副手
- ARMOR_SLOT - 盔甲槽

### 3. InventoryState.java
**背包状态类**
- 表示玩家当前的背包状态
- 使用哈希码实现快速状态比较
- 属性：
  - `type` - 状态类型
  - `slotContents` - 槽位内容
  - `hashCode` - 快速比较用哈希码

### 4. InventoryTransition.java
**状态迁移记录**
- 记录状态转换过程
- 属性：
  - `from` - 起始状态
  - `to` - 目标状态
  - `tick` - tick编号
  - `slotChanges` - 槽位变化列表

### 5. SlotChange.java
**槽位变化记录**
- 记录单个槽位的物品变化
- 属性：
  - `slot` - 槽位编号
  - `type` - 槽位类型
  - `fromItem` - 变化前物品
  - `toItem` - 变化后物品
  - `countChange` - 数量变化

### 6. InventoryViolationType.java
**违规类型枚举**
- INSTANT_SWAP - 瞬时交换 (85%)
- AUTO_TOTEM - 自动图腾 (90%)
- ILLEGAL_JUMP - 非法跳跃 (75%)
- MULTI_ITEM_SWAP - 多物品交换 (80%)
- CHAIN_REACTION - 连锁反应 (88%)

### 7. InventoryViolation.java
**背包违规类**
- 继承 `DetectionResult`
- 扩展背包相关的违规信息
- 属性：
  - `transitionSequence` - 迁移序列
  - `reactionTime` - 反应时间
  - `isChainReaction` - 是否连锁反应
  - `violationType` - 违规类型

### 8. AutoTotemDetector.java
**AutoTotem检测器**
- 检测自动图腾交换行为
- 核心方法：
  - `detectInstantSwap()` - 检测瞬时交换
  - `analyzeChainReaction()` - 分析连锁反应
  - `isZeroTickReaction()` - 检测0 tick反应
  - `detectMultiItemSwap()` - 检测多物品交换

**关键检测逻辑：**
- 同一tick内完成：关闭界面 → 图腾在副手 → 关闭界面
- 受伤后0 tick内完成图腾替换
- 同时完成多件物品移动

### 9. StateTransitionValidator.java
**状态迁移验证器**
- 验证每个迁移的合法性
- 核心方法：
  - `validateSequence()` - 验证序列
  - `detectIllegalJump()` - 检测非法跳跃
  - `calculateTransitionTime()` - 计算转换时间

**验证规则：**
- 从无界面到物品位置变化必须经过至少1 tick
- 图腾必须从背包移动到副手，不能凭空出现

### 10. ChainReactionAnalyzer.java
**连锁反应分析器**
- 分析受伤后的物品操作序列
- 核心方法：
  - `recordDamage()` - 记录伤害
  - `recordItemAction()` - 记录物品操作
  - `analyzeReactionTime()` - 分析反应时间
  - `detectAutoReaction()` - 检测自动反应

**人类反应时间标准：**
- 正常范围：300-1000ms
- 可疑范围：< 300ms
- 自动化脚本：0-50ms

### 11. InventoryEvidenceCollector.java
**证据收集器**
- 收集所有背包操作证据
- 核心方法：
  - `collectTransitionHistory()` - 收集转换历史
  - `collectSlotChanges()` - 收集槽位变化
  - `collectDamageEvents()` - 收集伤害事件
  - `serializeEvidence()` - 序列化证据

### 12. HumanReactionSimulator.java
**人类反应模拟器**
- 模拟正常人类反应时间
- 核心方法：
  - `getExpectedReactionTime()` - 获取预期反应时间
  - `getRandomizedReactionTime()` - 获取随机化反应时间
  - `isWithinHumanRange()` - 检查是否在人类范围内

## 使用方法

### 基本使用
```java
// 创建检测模块
InventoryDetectionModule module = new InventoryDetectionModule(plugin);

// 执行检测
Player player = ...;
InventoryViolation violation = module.check(player);

if (violation != null) {
    // 处理违规
    handleViolation(violation);
}
```

### 访问组件
```java
// 获取状态机
InventoryStateMachine stateMachine = module.getStateMachine();

// 获取AutoTotem检测器
AutoTotemDetector detector = module.getAutoTotemDetector();

// 获取连锁反应分析器
ChainReactionAnalyzer analyzer = module.getChainAnalyzer();
```

### 记录事件
```java
// 记录伤害事件
analyzer.recordDamage(playerUUID, "ZOMBIE", System.currentTimeMillis());

// 记录物品操作
SlotChange change = new SlotChange(40, SlotChange.SlotType.OFFHAND, null, totem, time, tick);
analyzer.recordItemAction(playerUUID, change, System.currentTimeMillis());
```

## 技术特点

1. **高效的状态比较**：使用哈希码快速比较状态
2. **完整的JavaDoc**：所有类和方法都有详细文档
3. **Java 8兼容**：使用Java 8兼容语法
4. **线程安全**：使用ConcurrentHashMap保证线程安全
5. **可扩展**：模块化设计，易于扩展新功能
6. **完整证据链**：收集所有相关证据用于分析

## 注意事项

- 所有文件使用UTF-8编码
- 遵循项目现有的代码风格
- 支持序列化以便持久化
- 完整的错误处理机制
