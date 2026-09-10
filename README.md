# Unisence IoT：轻松支撑百万设备与 10 万 QPS 的高性能物联网系统

> 面向工业设备的高性能物联网平台，覆盖设备建模、数据接入、时序存储、在线状态和规则计算。

**单机全链路实测轻松稳定处理 10 万 QPS，并已验证 30 万 QPS 稳定吞吐。** Unisence IoT 将管理控制台、设备接入、Kafka
数据链路、GreptimeDB 时序存储和规则运行时整合为一套完整的 IoT 数据基础设施，适合工业数据采集、设备管理和大规模遥测处理。

## 核心能力

- 产品、设备、标签和自定义设备表单管理；
- 属性与事件物模型；服务定义可在管理端维护，平台下发与回复尚未交付；
- MessagePack 紧凑消息与 Kafka 分区接入；
- GreptimeDB 属性当前值、历史、事件和上下线记录；
- 设备在线租约、离线扫描和状态跳变；
- 即时规则可配置、启停和执行（执行实现以免费二进制提供，源码不开放）；窗口规则可配置预览，执行需安装窗口运行时；
- 透传路由：时序库写入确认后，按产品与消息类型转发到已登记 Topic；
- MySQL 权威源、Redis 提示与本地不可变元数据快照；
- Vue 3 管理控制台、权限体系和运行数据查询。

版本能力与规则运行时安装方式见 [`docs/edition.md`](docs/edition.md)。

## 高性能设计

- **有界并发**：Java 21 虚拟线程组织阻塞流程，固定执行器限制 GreptimeDB 写入并发，不用无界线程换吞吐。
- **批量处理**：Kafka poll、消息解析、缓存读取、Redis pipeline 和时序写入贯穿同一批次，减少网络往返与对象分配。
- **可靠重放**：批次成功后才推进 offset，下游暂时不可用时数据保留在 Kafka。
- **事务规则状态**：状态变更、规则输出、DLQ 和输入 offset 在同一个 Kafka 事务中提交。
- **分层设备缓存**：紧凑目录、有界 L1、Redis L2 与 MySQL 权威回源，让内存主要服务于活跃设备。
- **低写放大在线状态**：高频续租在进程内合并，只有状态真正变化时才记录上下线跳变。

## 数据链路

```text
设备 / 协议驱动
        │
        ▼
      Kafka
        │
        ├──► 数据引擎 ──► 设备准入 / 物模型校验 ──► GreptimeDB
        │       ├──────► Redis 在线状态
        │       └──────► 透传路由（落库确认后）──► 已登记 Kafka Topic
        │
        └──► 规则服务 ──► 即时规则（窗口规则需窗口运行时）──► 已登记规则输出 Topic
                  └────► Kafka 事务状态 Topic

MySQL ──► 元数据同步 ──► 各运行实例的不可变本地快照
```

## 性能表现

当前单机、单 Kafka Broker、单 engine、单 rule-stream 的全链路压测中：

- 10,000 台设备；
- 90% 属性消息，每条包含 2 个 double 属性；
- 10% 事件消息；
- 2 条即时规则、2 条窗口规则；
- 300,004 QPS 持续 5 分钟稳定通过；
- 349,994 QPS 时规则 LAG 持续增长；
- 当前单实例稳定边界为 `[300k, 350k)`。
百万设备是当前架构的设计规模，尚未完成百万设备全链路实测。性能结果不构成任意生产环境的固定容量承诺，真实部署应按设备热点、消息大小、规则复杂度、Kafka副本和存储拓扑重新压测。

## 技术栈

- Java 21、Spring Boot、Vert.x、Gradle；
- Vue 3、TypeScript、Vite、Element Plus；
- Kafka、Redis、MySQL、GreptimeDB；
- MessagePack、Kafka Transactional Producer。

## 项目结构

```text
├── backend/         后端服务、数据引擎、规则运行时与共享模块
├── frontend/        Vue 3 管理控制台
├── docker-compose/  Kafka 与 GreptimeDB 本地部署示例
└── docs/            架构、驱动开发与压测文档
```

## 快速开始

环境要求：JDK 21、Gradle 9、Node.js 24、pnpm 11，以及 MySQL、Redis、Kafka 和 GreptimeDB。

```bash
cd backend
gradle :cn-service-admin-init:bootRun
gradle :cn-service-admin:bootRun
```

数据引擎和规则服务应分别在独立终端运行：

```bash
cd backend
gradle :cn-service-engine:run
gradle :cn-service-rule-stream:run
```

若使用已安装的 Community 规则运行时发行包，则改为启动：

```bash
./unisence-rule-runtime-community/bin/unisence-rule-runtime-community
```

本地联调可用测试用虚拟驱动产生上行流量；虚拟驱动不用于生产协议接入。窗口规则执行还须安装窗口运行时，见 [
`docs/edition.md`](docs/edition.md)。

启动管理控制台：

```bash
cd frontend
pnpm install
pnpm dev
```

初始化器要求 MySQL schema 已创建且为空；Kafka Topic、默认中间件地址和完整启动顺序见压测指南。

## 开发文档

- [系统架构指南](docs/architecture-guide.md)：驱动、Kafka、engine、rule-stream、元数据和时序存储如何协同。
- [驱动开发指南](docs/driver-development.md)：创建驱动模块、构造标准消息、可靠发布、背压与联调要求。

## 压测文档

- [全链路压测指南](docs/load-testing.md)：初始化中间件、创建 Topic、启动虚拟驱动、阶梯压测、观测与数据清理。
- [当前性能基准](docs/performance-benchmark.md)：300k QPS 通过、350k QPS 未通过的环境、负载与边界。
- [性能验证方法](docs/performance-methodology.md)：LAG 判定、积压排空、A/B、GC 和结果发布规则。

## 参与项目

欢迎通过 Issue 和 Pull Request 分享使用场景、问题与改进。提交性能结果时，请同时附上硬件、部署拓扑、消息模型、规则配置、持续时间和完整
LAG 曲线。

## 开源范围

本仓库对应 Unisence Community Edition（开源版本）。管理控制台、数据引擎、消息契约、规则配置与管理 API、规则服务启动器与配置模板是开源的。

**规则执行实现不是开源源码。** 窗口规则的执行代码不开放。即时规则执行与窗口规则写在一起，因此两者的执行实现都不作为开源源码提供。

开源版本的能力边界：

- **即时规则**：可以完整配置、启停和执行。执行能力由可免费安装的规则运行时二进制提供，本仓库不提供该运行时源码。
- **窗口规则**：可以在管理端创建、校验和保存（配置预览），不能启用执行；执行需要另外安装窗口运行时。

请勿把「Community 可免费使用即时规则」理解成「规则运行时源码全部开放」。
