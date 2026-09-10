# Unisence IoT

Unisence IoT 是面向工业设备的数据接入与处理平台。它将产品和设备管理、物模型、协议驱动接入、实时数据处理、时序存储、在线状态、规则计算和
Kafka 数据路由组织为一条完整的数据链路。

平台的重点是设备上行数据：协议驱动把设备报文转换为统一消息并写入
Kafka，数据引擎完成设备准入、物模型校验、在线状态维护与时序数据写入，规则服务根据配置生成规则输出。管理控制台用于维护元数据、规则、输出定义和运行数据查询。

## 功能

- **产品与设备管理**：维护产品、设备、标签、自定义设备表单，以及属性、事件和服务物模型。
- **协议接入**：通过驱动公共库将不同协议映射为统一的强类型消息；提供虚拟驱动用于开发和压测。
- **实时数据处理**：对属性、事件和心跳消息进行设备准入、物模型校验、在线状态维护和失败重放。
- **时序数据查询**：使用 GreptimeDB 存储属性当前值与历史、设备事件和上下线记录；控制台提供运行数据查询。
- **规则计算**：支持即时规则的过滤、条件判定、档位跃迁和 Kafka 输出；安装窗口运行时后可启用窗口规则。
- **数据路由**：时序写入确认后，可按产品和消息类型将原始上行消息透传至已登记的 Kafka Topic。
- **管理与权限**：提供用户、角色、菜单、按钮权限、字典和操作日志等管理能力。

当前平台不提供设备服务调用、远程配置和下行命令链路；物模型中的服务定义可以维护，但不会下发调用或接收回复。正式协议接入需要按协议实现或部署相应驱动，虚拟驱动仅用于测试和压测。

## 架构概览

```text
协议驱动
   │  认证、解析、标准消息映射
   ▼
 Kafka ────────────────┬─────────────────┐
   │                   │                 │
   ▼                   ▼                 │
数据引擎            规则服务             │
   │                   │                 │
   ├─ 设备准入          ├─ 即时规则        │
   ├─ 物模型校验        ├─ 窗口规则*       │
   ├─ 在线状态          └─ Kafka 规则输出  │
   ├─ GreptimeDB                         │
   └─ Kafka 透传路由 ◄────────────────────┘

MySQL ──► 元数据同步 ──► 数据引擎与规则服务本地快照
Redis  ──► 设备租约、状态交接与派生缓存
管理 API + Vue 控制台 ──► 元数据维护与运行数据查询

* 需要安装窗口运行时。
```

## 项目结构

```text
backend/         后端服务、协议驱动公共库、数据引擎、规则服务和共享模块
frontend/        Vue 3 管理控制台
docker-compose/  Kafka 与 GreptimeDB 本地部署示例
docs/            架构、驱动开发、版本能力和压测文档
```

## 本地运行

运行环境需要 JDK 21、Gradle 9、Node.js 24、pnpm 11，以及 MySQL、Redis、Kafka 和 GreptimeDB。请先按配置文件准备中间件、数据库和
Kafka Topic，然后执行初始化器：

```bash
cd backend
gradle :cn-service-admin-init:bootRun
```

再在各自独立的终端启动管理服务、数据引擎和规则服务：

```bash
cd backend
gradle :cn-service-admin:bootRun
```

```bash
cd backend
gradle :cn-service-engine:run
```

```bash
cd backend
gradle :cn-service-rule-stream:run
```

管理控制台可在另一终端启动：

```bash
cd frontend
pnpm install
pnpm dev
```

具体的初始化顺序、Topic 配置和本地联调方式请参阅[全链路压测指南](docs/load-testing.md)
。规则组件的可用能力和安装要求见[版本能力说明](docs/edition.md)。

## 文档

- [系统架构指南](docs/architecture-guide.md)：系统组件、数据流、消息类型与扩展方式。
- [驱动开发指南](docs/driver-development.md)：开发协议驱动并发布标准消息。
- [版本能力说明](docs/edition.md)：即时规则、窗口规则和可选组件的能力边界。
- [全链路压测指南](docs/load-testing.md)：本地初始化、压测执行、观测和清理。
- [性能基准](docs/performance-benchmark.md)：已完成场次的环境、负载模型、结果与适用边界。
- [性能验证方法](docs/performance-methodology.md)：性能结果的计时、LAG、对账和发布口径。

## 参与项目

欢迎通过 Issue 和 Pull Request 提交问题、使用反馈和改进建议。提交性能结果时，请附上硬件、部署拓扑、消息模型、规则配置、持续时间和完整
LAG 曲线。
