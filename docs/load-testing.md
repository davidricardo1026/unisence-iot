# Unisence IoT 全链路压测指南

本文说明如何从空环境初始化平台，使用虚拟驱动执行单机全链路阶梯压测，并正确判断一档负载是否稳定。

## 1. 环境要求

- JDK 21、Gradle 9；
- MySQL 8、Redis、Kafka 4、GreptimeDB 1.3；
- 推荐使用独立压测环境，确保测试数据可以安全删除；
- Kafka 与 GreptimeDB 的本地部署示例位于 `docker-compose/`。

默认 `dev` 配置使用：

| 中间件                 | 地址                | 数据库/认证                   |
|---------------------|-------------------|--------------------------|
| MySQL               | `127.0.0.1:8045`  | `unisence_iot`，`root/ss` |
| Redis               | `127.0.0.1:6380`  | DB 6，密码 `ss`             |
| Kafka               | `127.0.0.1:19092` | PLAINTEXT                |
| GreptimeDB gRPC     | `127.0.0.1:4001`  | 无认证                      |
| GreptimeDB MySQL 协议 | `127.0.0.1:4002`  | 无认证                      |

这些值仅用于本地开发。其他环境应通过环境变量覆盖端点与凭据，不要提交真实密钥。

## 2. 创建数据库和 Kafka Topic

初始化器要求目标 MySQL schema 已存在且没有业务表：

```bash
mysql -h 127.0.0.1 -P 8045 -u root -p -e "CREATE DATABASE IF NOT EXISTS unisence_iot CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"
```

规则状态 Topic 的分区必须与对应输入 Topic 完全一致，且 `cleanup.policy` 必须包含 `compact`。以下命令用于单 Broker 本地压测：

```bash
docker exec kafka431 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic iot.raw.data --partitions 48 --replication-factor 1
docker exec kafka431 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic iot.event --partitions 12 --replication-factor 1
docker exec kafka431 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic iot.rule.state.property.v1 --partitions 48 --replication-factor 1 --config cleanup.policy=compact
docker exec kafka431 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic iot.rule.state.event.v1 --partitions 12 --replication-factor 1 --config cleanup.policy=compact
docker exec kafka431 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic iot.rule.output --partitions 12 --replication-factor 1
docker exec kafka431 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic iot.route.property --partitions 12 --replication-factor 1
docker exec kafka431 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists --topic iot.raw.dlq --partitions 3 --replication-factor 1
```

正式集群必须按容量规划配置副本数和 `min.insync.replicas`。已经产生规则状态后，不要随意增加输入 Topic 分区，否则设备 key
与状态分区的映射会发生变化。

## 3. 初始化平台

```bash
cd backend
gradle :cn-service-admin-init:bootRun
```

初始化器会创建 MySQL、GreptimeDB 表和演示数据。目标 MySQL schema 非空时会拒绝执行，避免误覆盖。

初始化后可直接使用测试产品：

- `productKey`：`syt57v`；
- 属性：`temperature`；
- 事件：`temperature_alarm`；
- 设备表单必填字段：`area`；
- 规则：1 条透传（属性）、1 条即时（温度跃迁）、1 条窗口（温度告警计数，仅窗口运行时执行）。

## 4. 启动数据链路

分别在独立终端启动：

```bash
cd backend
gradle :cn-service-admin:bootRun
```

```bash
cd backend
gradle :cn-service-engine:run
```

源码树启动即时规则服务：

```bash
cd backend
gradle :cn-service-rule-stream:run
```

若使用已安装的 Community 发行包：

```bash
./unisence-rule-runtime-community/bin/unisence-rule-runtime-community
```

种子数据包含 1 条透传、1 条即时和 1 条窗口规则。Community 运行时只执行即时规则与透传所需的数据面；窗口规则要真正执行，须安装窗口运行时并以
`-PincludeWindowRuntime=true` 启动（或使用对应专业版启动器）。当前公开的 300k QPS 基准是在窗口运行时已安装、2 条即时 + 2
条窗口规则的配置下测得的，不能直接当成
Community 即时规则的容量数字。

确认 MySQL、Redis、Kafka、GreptimeDB 连接正常，并等待 engine、rule-stream 完成元数据初始收敛后再启动虚拟驱动。正式容量测试可以停止
Admin，避免把管理 API 资源计入数据链路。engine 启动命令须包含 `-Dgreptimedb.use_os_signal=false`。

## 5. 低流量冒烟

`cn-driver-virtual` 只用于开发、联调和容量测试，不用于生产协议接入。

```bash
cd backend
LOAD_ENABLED=true \
LOAD_PRODUCT_KEY=syt57v \
LOAD_DEVICE_COUNT=10000 \
LOAD_PROPERTY_IDENTIFIERS=temperature \
LOAD_TARGET_RPS=1000 \
LOAD_THREADS=4 \
LOAD_WARMUP=30s \
LOAD_DURATION=2m \
gradle :cn-service-driver:cn-driver-virtual:bootRun
```

负载模式会先批量创建设备，等待元数据收敛，再按目标速率发送数据。Kafka 不在默认地址时设置 `KAFKA_SERVERS=host:port`
。压测自定义产品时，属性、事件和表单字段必须与真实物模型一致，否则测到的是业务拒绝路径。

## 6. 阶梯压测

冒烟通过后，固定设备数、消息模型、规则、Topic、中间件和 JVM 配置，只调整 `LOAD_TARGET_RPS`。每档建议持续 3–5 分钟：

```text
10k → 20k → 40k → 60k → 100k → 150k → 200k → 250k → 300k
```

每档重新启动虚拟驱动，并设置对应 `LOAD_TARGET_RPS` 和 `LOAD_DURATION`。先找到稳定档和首个失败档，再在两者之间缩小范围，不要一开始直接冲击峰值。

热点设备场景可在完整启动命令前设置：

```bash
export APP_DRIVER_VIRTUAL_LOAD_HOT_DEVICE_COUNT=1
export APP_DRIVER_VIRTUAL_LOAD_HOT_DEVICE_SHARE=0.3
```

事件和心跳负载分别通过 `LOAD_EVENT_RATIO`、`LOAD_EVENT_IDENTIFIERS`、`LOAD_EVENT_PARAMS` 和 `LOAD_DEVICE_HEARTBEAT_RATIO`
配置。不同消息模型必须分开记录结果。

## 7. 稳定判定

虚拟驱动达到目标速率不等于服务端能够持续处理。至少记录：

- 驱动实际 QPS、成功数、失败数和发送延迟；
- engine 的 `cn-service-engine-storage`、`cn-service-engine-event` 消费组 LAG；
- rule-stream 的 `cn-service-rule-stream-v5` 消费组 LAG；
- `iot.raw.dlq` 是否出现非预期记录；
- Kafka 输入、规则接收、规则输出和 GreptimeDB 落库对账；
- JVM、Kafka、GreptimeDB、MySQL 和 Redis 的资源水位；
- 停止发送后各消费组的排空时间。

```bash
docker exec kafka431 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cn-service-engine-storage
docker exec kafka431 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cn-service-engine-event
docker exec kafka431 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group cn-service-rule-stream-v5
```

一档只有在持续窗口内发送失败为 0、LAG 不单调增长、停止发送后 LAG 归零且数据对账成立时才算通过。失败档最终能够排空，不代表它可以承载持续业务。

## 8. 正式场次清理

每个独立正式场次结束后：

1. 停止 driver、engine、rule-stream 和监控；
2. 保存 driver 报告、LAG、Topic 对账、日志、GC/JFR 和资源证据；
3. 使用 `rpk` 保存显式清单中业务 Topic、显式 state Topic 与本场自建 Topic 的分区数、副本数和动态配置；
4. 确认消费组无活跃成员后删除本场 engine、rule-stream 消费组，再按显式清单删除 Topic 并按快照原规格重建；
5. 对 GreptimeDB 涉及的运行表执行 `TRUNCATE TABLE`；
6. 保留重建后的 Kafka Topic，以及 GreptimeDB 表、catalog、database、volume 和容器；
7. 不清理或重建 MySQL、Redis；
8. 确认 Kafka Topic 规格与删除前一致、逐分区 `earliest=latest=0`，用 `read_committed` 确认可读记录数为 0，
   并逐表确认 GreptimeDB 记录数为 0。

这些删除操作只适用于状态可丢弃的隔离压测环境。生产环境或需要恢复状态的环境禁止清理 state Topic。

结果解释见[性能基准](performance-benchmark.md)，判定原则见[性能验证方法](performance-methodology.md)。
