# Unisence IoT 架构指南

## 1. 系统定位

Unisence IoT 是一套面向工业设备的数据接入与处理平台。协议驱动把设备报文转换为统一消息，经 Kafka
进入数据引擎和规则服务；数据引擎负责设备准入、物模型校验、在线状态、时序存储和透传路由，规则服务负责即时计算（窗口计算需安装窗口运行时），并把结果写到管理端已登记的
Kafka Topic。

对大多数接入项目来说，平台核心链路无需修改。开发者通常只需要：

1. 在管理端定义产品、设备表单和物模型；
2. 基于 `cn-driver-common` 开发协议驱动，或部署已安装的正式协议驱动；
3. 将协议字段映射为平台的五类标准消息；
4. 按需消费已登记的规则输出 Topic 或透传 Topic，接入自建通知、工单或其他业务系统。

平台当前不提供设备服务调用、远程配置或下行命令链路。物模型服务定义可以保存，但不会下发或收回复。测试用虚拟驱动只用于开发与压测，不能作为生产协议接入。

## 2. 总体架构

```text
协议驱动（common + 自研 / 正式协议驱动；virtual 仅测试）
              认证 → 解析 → 映射 → 发布
                         │
          ┌──────────────┴──────────────┐
          ▼                             ▼
  Kafka iot.raw.data             Kafka iot.event
     属性遥测                  建档 / 事件 / 心跳
          │                             │
          ├──────────────┬──────────────┤
          ▼              ▼              ▼
      数据引擎       规则服务       Kafka 持久积压
       engine       rule-stream         │
          │              │              └─ 故障后重放
          │              ├─ 即时规则
          │              ├─ 窗口规则（需窗口运行时）
          │              ├─ 事务状态 Topic
          │              └─ 已登记 RULE_OUTPUT Topic
          │
          ├─ GreptimeDB：属性当前值 / 历史 / 事件 / 上下线
          ├─ 透传路由：落库确认后 → 已登记 ROUTE Topic
          └─ Redis：设备租约 / 状态跳变交接 / 派生缓存

MySQL：产品 / 设备 / 物模型 / 规则权威数据
  │
  └─ 元数据同步 → engine 与 rule-stream 的不可变本地快照

Admin API + Vue 管理端：配置、权限与运行数据查询
```

## 3. 主要模块

| 模块                       | 职责                                                 | 不负责                   |
|--------------------------|----------------------------------------------------|-----------------------|
| `cn-driver-common`       | 标准消息依赖、MessagePack 编码、Kafka key、header 和双 Topic 发布 | 具体协议解析                |
| `cn-driver-virtual`      | 测试与压测用虚拟上行                                         | 生产协议接入                |
| `cn-driver-*`            | 连接设备或网关、认证、解析协议、转换标准消息、处理发送确认                      | 时序落库和规则执行             |
| Kafka                    | 解耦生产与消费、分区有序、持久积压和故障重放                             | 业务字段校验                |
| `cn-service-engine`      | 设备准入、物模型校验、在线续租、GreptimeDB 写入、透传路由                 | HTTP API 和规则状态        |
| Community 规则运行时          | 即时规则、规则过滤、事务微批和规则输出                                | 时序数据库写入、HTTP API、窗口执行 |
| 窗口运行时                    | 窗口聚合、到期结算和窗口规则输出                                   | 设备接入；未安装时窗口规则不可启用     |
| `cn-metadata-sync`       | 产品、物模型、设备和规则的增量收敛与分层缓存                             | 服务生命周期和业务 API         |
| `cn-service-admin`       | 管理 API、权限、配置和运行数据只读查询                              | 设备数据面消费               |
| `cn-service-admin-init`  | 空库 DDL、种子数据和测试物模型初始化                               | 数据库迁移与覆盖更新            |
| `cn-timeseries-greptime` | GreptimeDB gRPC 写入、MySQL 协议查询和事件表供给                | 业务消息路由                |

## 4. 五类上行消息

驱动只能发布平台定义的强类型消息，不能自行拼装 Kafka value、key 或 header。

| 消息类型                      | 用途           | Kafka Topic    | 主要消费者              |
|---------------------------|--------------|----------------|--------------------|
| `DeviceCreateMessage`     | 从可信驱动批量建档    | `iot.event`    | engine             |
| `DevicePropertyMessage`   | 属性遥测         | `iot.raw.data` | engine、rule-stream |
| `DeviceEventMessage`      | 设备事件         | `iot.event`    | engine、rule-stream |
| `DeviceHeartbeatMessage`  | 无业务数据时维持设备租约 | `iot.event`    | engine             |
| `ServiceHeartbeatMessage` | 维持驱动实例租约     | `iot.event`    | engine             |

所有设备消息使用 `productKey.deviceCode` 作为 Kafka key，在各自 Topic 内保持同一设备有序。服务心跳使用独立的
`service.<serviceName>.<instanceId>` key。需要注意，建档与属性分属 `iot.event` 和 `iot.raw.data`，Kafka 不保证跨 Topic
顺序；首次建档后应等待元数据收敛，再开始发送属性。

## 5. 数据引擎处理流程

### 5.1 属性链路

```text
iot.raw.data
  → 解码与结构校验
  → 批量设备准入
  → 在线租约续期
  → 物模型属性标识符与类型校验
  → 按值类型和保留周期分组
  → GreptimeDB gRPC 批量写入
  → 透传路由（若该产品有 ROUTE 绑定）
  → 提交 Kafka offset
```

设备准入先于续租和物模型校验。确认未注册的设备会被丢弃，不落库、不续租，也不进入 DLQ；无法确认是“不存在”还是“实例尚未收敛”时，系统会先执行权威修复。

未知属性、类型不符等确定性数据问题会进入 DLQ。GreptimeDB 暂时不可用属于可重试基础设施故障，当前批次不提交 offset，恢复后从
Kafka 重放。

### 5.2 事件与心跳链路

```text
iot.event
  ├─ 建档：批量事务写 MySQL → 等待元数据水位收敛
  ├─ 事件：设备准入 → 续租 → 物模型校验 → GreptimeDB → 透传路由
  ├─ 设备心跳：设备准入 → 续租
  └─ 服务心跳：更新驱动实例租约
```

属性、事件和设备心跳都可以证明设备最近活跃。在线状态使用平台接收时间续租，不直接信任设备时钟；只有上线或离线状态真正变化时才写入历史记录。

## 6. 规则服务处理流程

规则服务直接消费 `iot.raw.data` 和 `iot.event`，不依赖 engine 产生中间 Topic，因此存储链路与规则链路可以分别扩缩容和恢复。

```text
属性 / 事件消息
  → 解码与设备准入
  → 读取本地规则快照
  → 候选规则索引
  → 监听条件与脚本过滤
  → 即时计算，或在窗口运行时中累积
  → 判档、恢复与输出转换
  → Kafka 事务提交
```

每个分区 worker 独占已提交内存状态，当前批次的变化进入 `BatchDelta`。以下内容由同一个 Kafka Transactional Producer 原子提交：

- 规则状态变更；
- 档位绑定的全部 `purpose=RULE_OUTPUT` Topic；
- 规则侧 DLQ；
- 输入消费 offset。

即时规则有两种输出节奏：`LEVEL_TRANSITION`（默认，仅档位变化时输出）与 `EVERY_MATCH`（每条命中都执行输出脚本）。窗口规则只有跃迁输出。目标
Topic 必须先在管理端登记，不能由脚本或消息动态决定。未安装窗口运行时时，窗口规则不能启用，也不会消费或产出。

显式 compacted state Topic 是规则状态的持久化真相源。进程重启或分区迁移时，worker 先恢复状态，再继续处理输入，避免出现“状态已推进但输出丢失”或“输出成功但
offset 未提交”的半提交状态。

## 7. 元数据与缓存

MySQL 是产品、物模型、设备和规则的权威源。管理事务同时记录提交水位和变化范围，Redis 只发送低延迟提示；即使提示丢失，实例也能通过水位探测和
MySQL 定时反熵发现差异。

engine 与 rule-stream 使用同一套元数据同步语义：

- 后台构建候选不可变快照，完成后原子替换；
- 收敛失败时继续使用 Last Known Good；
- 设备存在性使用紧凑目录和 Bloom；
- 完整设备投影进入有界本地 L1；
- Redis L2 是可重建派生缓存；
- 冷 miss 最终有界批量回源 MySQL。

这让高频消息热路径主要读取本地内存，而不是逐条查询 MySQL 或 Redis。

## 8. 存储与查询

GreptimeDB 是统一时序存储：

- engine 通过 gRPC 写入属性、事件和上下线记录；
- 设备当前值由 GreptimeDB 按类型与保留档位表派生，不另建 Redis 最新值层；
- Admin 通过 MySQL 协议只读查询；保存事件定义时可由 Admin 供给事件表 DDL，但不写运行数据；
- 属性历史和事件查询单次时间跨度最多 7 天；
- 趋势查询在数据库侧使用 `date_bin` 降采样；
- rule-stream 不写时序数据库。

管理流量和数据面运行在独立服务中，复杂查询不会占用 engine 的消费线程。

## 9. 扩展系统

### 接入新协议

新增 `cn-driver-<protocol>` 模块，依赖 `cn-driver-common`，将协议报文转换为标准 `IotMessage` 后交给 `DeviceDataPublisher`
。详细要求见[驱动开发指南](driver-development.md)。Community 发行只包含公共库与虚拟测试驱动；正式协议驱动按实际安装制品提供。

### 增加规则结果或透传处理

在管理端登记 Kafka 输出定义（精确 Topic、格式、用途），再绑定到规则档位（`RULE_OUTPUT`）或透传路由（`ROUTE`）。下游消费这些
Topic，根据规则 ID、触发类型和业务字段接入通知、工单或自建数据面。下游必须自行保证幂等，不能假设网络重试永远不会产生重复投递。平台不托管
HTTP、外部 Kafka、短信或工单转发。

### 扩展时序查询

业务服务只依赖 `cn-timeseries-api`，数据库方言和供应商 SDK 集中在 `cn-timeseries-greptime`。新增查询应保持 Admin
只读、时间跨度不超过 7 天，并对返回点数和页大小设明确上限。

## 10. 部署与扩容原则

- Kafka 分区数决定消费并行上限，也影响设备 key 的状态归属，建 Topic 前应按容量规划确定；
- engine、rule-stream、Admin 和驱动使用独立进程，按各自瓶颈扩容；
- rule-stream 新实例接管分区前需要恢复对应 state Topic；
- Kafka、GreptimeDB、MySQL 和 Redis 的副本、磁盘与连接容量必须联合压测；
- 不用无界线程、无限缓存或无限重试队列掩盖下游瓶颈；
- 生产容量必须以真实设备分布、消息大小、规则复杂度和完整 LAG 曲线为准。

压测操作见[全链路压测指南](load-testing.md)，当前实测边界见[性能基准](performance-benchmark.md)。
