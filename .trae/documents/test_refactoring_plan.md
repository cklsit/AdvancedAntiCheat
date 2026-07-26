# 测试项目重构计划

## 一、现状分析

### 1.1 当前测试结构
根据现有的 `Release-Pipeline.ps1` 脚本分析，当前测试流程包括：
- **构建阶段**: Maven clean package
- **高版本测试**: Paper 1.21.11 服务端测试
- **低版本测试**: Paper 1.8.8 服务端测试
- **提交阶段**: GitHub commit & push

### 1.2 存在的问题
1. **测试文件夹缺失**: 脚本中引用的 `test/high` 和 `test/low` 文件夹不存在
2. **测试覆盖不足**: 缺少单元测试、集成测试和回归测试
3. **测试工具缺失**: 没有测试框架和断言库
4. **报告机制不完善**: 缺少测试报告生成和问题追踪
5. **自动化程度低**: 需要手动配置测试环境

## 二、重构目标

### 2.1 目标概述
- 建立完整的测试体系，覆盖单元测试、集成测试和端到端测试
- 创建标准化的测试文件夹结构
- 实现自动化测试流程
- 生成详细的测试报告

### 2.2 测试类型覆盖
| 测试类型 | 描述 | 覆盖范围 |
|---------|------|---------|
| **单元测试** | 测试单个类/方法 | 核心检测逻辑、工具类 |
| **集成测试** | 测试模块间交互 | 检测器协同、数据库操作 |
| **服务端测试** | 测试 Minecraft 服务端 | Paper 1.8.8 ~ 1.21.x |
| **Web 面板测试** | 测试前端功能 | API 接口、UI 交互 |

## 三、重构方案

### 3.1 文件夹结构设计

```
test/
├── unit/                          # 单元测试
│   ├── java/                      # Java 单元测试
│   │   └── com/anticheat/         # 测试包结构
│   ├── resources/                 # 测试资源
│   └── pom.xml                    # 测试依赖配置
├── integration/                   # 集成测试
│   ├── java/
│   ├── resources/
│   └── docker-compose.yml         # 测试环境容器配置
├── server/                        # 服务端测试
│   ├── high/                      # Paper 1.21.x
│   │   ├── plugins/               # 插件目录
│   │   ├── logs/                  # 日志目录
│   │   ├── server.properties      # 服务端配置
│   │   └── paper.jar              # Paper 服务端
│   └── low/                       # Paper 1.8.8
│       ├── plugins/
│       ├── logs/
│       ├── server.properties
│       └── paper.jar
├── web/                           # Web 面板测试
│   ├── cypress/                   # Cypress E2E 测试
│   └── postman/                   # Postman API 测试
└── reports/                       # 测试报告
    ├── unit/
    ├── integration/
    └── server/
```

### 3.2 新增文件清单

| 文件路径 | 描述 | 类型 |
|---------|------|------|
| `test/unit/pom.xml` | 单元测试依赖配置 | 配置文件 |
| `test/unit/java/com/anticheat/detection/ViolationManagerTest.java` | 违规管理器测试 | 测试文件 |
| `test/unit/java/com/anticheat/profiles/PlayerProfileTest.java` | 玩家档案测试 | 测试文件 |
| `test/integration/docker-compose.yml` | 数据库测试环境 | 配置文件 |
| `test/server/high/server.properties` | 高版本服务端配置 | 配置文件 |
| `test/server/low/server.properties` | 低版本服务端配置 | 配置文件 |
| `test/web/cypress/e2e/login.cy.js` | 登录页面 E2E 测试 | 测试文件 |
| `test/web/postman/api-test.postman_collection.json` | API 测试集合 | 测试文件 |
| `test/reports/.gitkeep` | 报告目录占位文件 | 占位文件 |

### 3.3 测试配置说明

#### 3.3.1 单元测试依赖
- **JUnit 5**: Java 单元测试框架
- **Mockito**: 模拟对象框架
- **AssertJ**: 流式断言库

#### 3.3.2 服务端测试配置
- **Paper 1.21.11**: 高版本测试
- **Paper 1.8.8**: 低版本测试
- **server.properties**: 统一配置模板

## 四、重构步骤

### 4.1 步骤概述

| 步骤 | 任务 | 负责人 | 预计时间 |
|------|------|--------|----------|
| 1 | 创建测试文件夹结构 | 自动 | 10分钟 |
| 2 | 添加单元测试配置和测试用例 | 自动 | 30分钟 |
| 3 | 创建服务端测试环境 | 自动 | 20分钟 |
| 4 | 添加 Web 面板测试 | 自动 | 20分钟 |
| 5 | 更新发布管道脚本 | 自动 | 15分钟 |
| 6 | 验证测试流程 | 手动 | 30分钟 |

### 4.2 详细步骤

**步骤 1: 创建测试文件夹结构**
```powershell
# 创建基础目录
mkdir -p test/unit/java/com/anticheat
mkdir -p test/unit/resources
mkdir -p test/integration/java/com/anticheat
mkdir -p test/integration/resources
mkdir -p test/server/high/{plugins,logs}
mkdir -p test/server/low/{plugins,logs}
mkdir -p test/web/{cypress/e2e,postman}
mkdir -p test/reports/{unit,integration,server}
```

**步骤 2: 添加单元测试配置**
- 创建 `test/unit/pom.xml`
- 添加 JUnit 5、Mockito、AssertJ 依赖

**步骤 3: 创建服务端测试环境**
- 创建 `test/server/high/server.properties`
- 创建 `test/server/low/server.properties`

**步骤 4: 更新发布管道脚本**
- 添加单元测试执行步骤
- 添加测试报告生成
- 增强错误处理和日志记录

## 五、风险评估

| 风险 | 描述 | 影响 | 缓解措施 |
|------|------|------|----------|
| **服务端版本兼容** | Paper 版本差异可能导致测试失败 | 高 | 使用 Docker 容器隔离测试环境 |
| **测试环境缺失** | 缺少 Paper jar 文件 | 中 | 提供下载脚本或使用容器 |
| **测试时间过长** | 多版本测试耗时较长 | 中 | 并行执行测试 |
| **资源占用** | 多个服务端同时运行内存占用高 | 中 | 顺序执行测试并及时清理 |

## 六、预期成果

### 6.1 测试覆盖提升
- ✅ 单元测试覆盖核心检测逻辑
- ✅ 集成测试验证模块交互
- ✅ 多版本服务端兼容性测试
- ✅ Web 面板功能回归测试

### 6.2 自动化能力
- ✅ 一键执行所有测试
- ✅ 自动生成测试报告
- ✅ 失败时自动终止并报告
- ✅ 测试结果可视化

### 6.3 可维护性
- ✅ 标准化测试结构
- ✅ 清晰的测试分类
- ✅ 易于添加新测试用例
- ✅ 详细的文档说明

---

**文档版本**: v1.0  
**创建日期**: 2026-06-03  
**适用项目**: AdvancedAntiCheat