# 驱动开发指南

## 1. 驱动负责什么

驱动是设备协议与平台统一数据模型之间的适配层。一个驱动通常只做五件事：

1. 建立并维护设备、网关或上游服务连接；
2. 完成认证、限流、报文边界检查和协议解析；
3. 将外部设备标识映射为平台的 `productKey + deviceCode`；
4. 将报文转换为平台定义的强类型 `IotMessage`；
5. 通过 `DeviceDataPublisher` 可靠发布到 Kafka，并根据发送结果确认或重试。

驱动不负责时序落库、设备在线状态计算、规则执行或直接维护平台数据库。这些能力由 engine、rule-stream 和 Admin 统一提供。

Community 提供 `cn-driver-common` 与测试用 `cn-driver-virtual`。虚拟驱动只用于开发、联调和压测，禁止生产协议接入。正式
MQTT / Modbus / OPC UA 等驱动按实际安装制品提供；未安装时，应基于 common 自研协议模块。

## 2. 复用 `cn-driver-common`

新驱动不需要继承某个 Java 基类，而是在 Gradle 中依赖 `cn-driver-common`。该公共模块已经提供：

- 五类标准消息及其结构校验；
- MessagePack 编码器；
- Kafka Topic 路由；
- 设备分区 key 与消息类型 header；
- `DeviceDataPublisher.publish(IotMessage)` 发布入口。

因此，驱动不要自行编码 MessagePack，也不要自行拼接 Topic、Kafka key 或 `mt` header。

### 2.1 创建模块

在 `backend/cn-service-driver/` 下创建模块，例如 `cn-driver-example`，并在 `backend/settings.gradle` 中加入：

```groovy
include 'cn-service-driver:cn-driver-example'
```

模块的 `build.gradle` 可以从下面的最小配置开始：

```groovy
plugins {
    id 'org.springframework.boot'
    id 'io.spring.dependency-management'
}

apply from: rootProject.file('gradle/spring-boot-app.gradle')

dependencies {
    implementation project(':cn-service-driver:cn-driver-common')
    // 在这里增加协议 SDK，例如 MQTT、Modbus 或 OPC UA 客户端。
}

tasks.named('bootJar') {
    archiveBaseName = 'cn-driver-example'
}
```

启动类使用标准 Spring Boot 应用即可：

```java

@SpringBootApplication
public class ExampleDriverApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExampleDriverApplication.class, args);
    }
}
```

## 3. Kafka 配置

驱动的项目配置统一放在 `app:` 下，Kafka 客户端仍使用 Spring Boot 官方配置：

```yaml
spring:
  application:
    name: cn-driver-example
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.ByteArraySerializer
      acks: all
      properties:
        enable.idempotence: true

app:
  driver:
    kafka:
      raw-data-topic: iot.raw.data
      event-topic: iot.event
    example:
      enabled: true
```

生产环境还应配置 Kafka TLS/SASL、凭据注入、连接超时和可观测性。批大小、并发数、缓冲区和超时等性能参数应通过真实压测集中定值，不要照搬其他环境的数值。

## 4. 标准消息

### 4.1 公共信封字段

五类消息都包含以下字段：

| 字段              | 要求                                                   |
|-----------------|------------------------------------------------------|
| `schemaVersion` | 使用 `MessagePackMessageCodec.SCHEMA_VERSION`，不要硬编码版本号 |
| `msgId`         | 全局唯一，最长 36 字符；同一业务消息重试时必须复用                          |
| `occurredAt`    | 设备侧业务实际发生时间，Unix epoch millis，必须为正数                  |
| `source`        | 可信来源标识，最长 120 字符；应来自已认证连接或服务上下文，不能直接信任报文字段           |

设备消息还必须携带：

| 字段           | 要求                    |
|--------------|-----------------------|
| `productKey` | 6 位小写字母或数字，由平台产品映射确定  |
| `deviceCode` | 产品内设备唯一编码，非空且最长 50 字符 |

### 4.2 五类消息及用途

| 消息                        | 必要业务字段                 | 使用场景          | Topic          |
|---------------------------|------------------------|---------------|----------------|
| `DeviceCreateMessage`     | 设备名、节点类型、网关编码、动态表单     | 可信驱动代设备建档     | `iot.event`    |
| `DevicePropertyMessage`   | 交付纪元、交付序号、非空属性 map     | 温度、压力、位置等遥测   | `iot.raw.data` |
| `DeviceEventMessage`      | 交付纪元、交付序号、事件标识符、参数 map | 告警、故障、动作等离散事件 | `iot.event`    |
| `DeviceHeartbeatMessage`  | 心跳状态、白名单诊断指标           | 没有业务数据时维持设备租约 | `iot.event`    |
| `ServiceHeartbeatMessage` | 服务名、实例 ID、启动时间         | 维持驱动实例租约      | `iot.event`    |

`DeviceDataPublisher` 自动完成上述路由。设备消息的 Kafka key 为 `productKey.deviceCode`，服务心跳 key 为
`service.<serviceName>.<instanceId>`。

## 5. 发布一条属性消息

下面的处理器只展示平台接入部分。协议连接、设备映射和序号持久化应由具体驱动实现：

```java
package com.unisence.iot.driver.example;

import com.unisence.iot.driver.common.kafka.DeviceDataPublisher;
import com.unisence.iot.message.DevicePropertyMessage;
import com.unisence.iot.message.codec.MessagePackMessageCodec;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public final class ExampleUplinkHandler {
    private final DeviceDataPublisher publisher;

    public ExampleUplinkHandler(DeviceDataPublisher publisher) {
        this.publisher = publisher;
    }

    public CompletableFuture<SendResult<String, byte[]>> onTelemetry(
            String msgId,
            long occurredAt,
            String authenticatedSource,
            long deliveryEpoch,
            long deliverySequence,
            String productKey,
            String deviceCode,
            double temperature) {

        var message = new DevicePropertyMessage(
                MessagePackMessageCodec.SCHEMA_VERSION,
                msgId,
                occurredAt,
                authenticatedSource,
                deliveryEpoch,
                deliverySequence,
                productKey,
                deviceCode,
                Map.of("temperature", temperature)
        );
        return publisher.publish(message);
    }
}
```

`values` 的 key 必须是产品物模型中已定义的属性标识符，value 类型也必须匹配物模型。任一属性未知或类型不符时，整条消息都会被拒绝，不会只保存其中一部分。

调用方必须观察 `publish` 返回的 future。只有 Kafka 确认成功后，才可以向支持确认机制的设备协议返回成功；失败时应按协议语义重试同一条消息。

## 6. 各消息的关键约束

### 6.1 设备创建

- `productKey + deviceCode` 是幂等设备标识；
- `DIRECT` 和 `GATEWAY` 不得携带 `gatewayCode`；
- `SUB_DEVICE` 必须携带所属网关的 `gatewayCode`；
- `formData` 必须满足该产品设备表单的必填项和类型定义；
- 已存在设备的冲突字段不会被静默覆盖。

设备创建走 `iot.event`，属性走 `iot.raw.data`。Kafka 只能保证同一 Topic 分区内有序，不能保证两个 Topic
之间的处理先后。因此，驱动批量建档后必须等待元数据在 engine 和 rule-stream 中收敛，再开始发送属性；不能用“创建消息先发出”代替就绪确认。

### 6.2 属性与事件

属性和事件都带有 `deliveryEpoch` 与 `deliverySequence`：

- `deliveryEpoch` 必须为正数，用来区分发送者纪元；
- `deliverySequence` 在同一设备、同一消息流和同一纪元内单调递增，从 0 开始即可；
- 首次发送前就要分配并持久化 `msgId`、纪元和序号；
- Kafka 发送失败、连接断开或协议重传时，必须复用原消息的全部标识；
- 切换新纪元前应先隔离旧发送者，避免两个实例同时为同一设备分配序号。

生产驱动通常应使用本地持久化 outbox 或等价机制，把“已接收协议报文”和“待发布标准消息”作为可恢复状态管理。不要在每次重启后从临时内存随意生成新纪元和序号。

事件的 `identifier` 和 `params` 必须与物模型事件定义一致。事件等级由平台物模型确定，驱动无需也不应自行上报事件等级。

### 6.3 设备心跳

有属性或事件数据时，平台已经会续期设备在线租约；只有设备长时间没有业务数据时，才需要单独发送设备心跳。

心跳 `metrics` 只接受这些诊断键：`rssi`、`snr`、`battery`、`uptimeMillis`、`memUsedRatio`、`cpuLoad`、`firmwareVersion`
。普通遥测必须走属性消息，不能借心跳绕过物模型校验。

### 6.4 服务心跳

每个驱动实例使用稳定且唯一的 `instanceId`，并在整个进程生命周期内复用同一个 `startedAt`。重启后使用新的启动时间。发送周期应明显短于平台配置的服务租约
TTL，以容忍短暂网络抖动。

## 7. 设备身份与准入

驱动应从已认证的连接、证书、网关绑定或服务端映射中确定设备身份，不能直接相信报文自报的 `source`、`productKey` 或
`deviceCode`。

属性、事件和设备心跳进入平台后都会先检查设备是否存在：

- engine 对已确认不存在的设备不落时序库、不续租，也不写 engine DLQ；规则链路会按自身的数据质量策略留存拒绝记录；
- 元数据暂未收敛时，平台会先尝试权威修复；
- 需要自动建档时，应先发送合法的 `DeviceCreateMessage` 并等待收敛；
- 不允许自动建档时，应在驱动侧拒绝未绑定设备并给出可观测的原因。

## 8. 背压、重试与停机

- 对未完成的 Kafka future 设置明确上限；达到上限时暂停读、降低协议接收窗口或拒绝新请求；
- 不要使用无限队列，也不要为每条消息创建线程；
- 重试使用指数退避和随机抖动，并设置运维可见的重试状态；
- Kafka 长时间不可用时，优先让压力回传到设备协议或落入有界、可恢复的 outbox；
- 驱动停机时先停止接收新报文，再等待在途发布完成，最后关闭协议连接和 Kafka producer；
- 不要“只调用 publish 不处理结果”，否则协议侧可能收到成功而消息实际未进入 Kafka。

并发上限、批大小、超时和重试窗口都与设备协议、消息尺寸和 Kafka 容量有关，应通过压测确定。

## 9. 安全与可观测性

生产驱动至少应具备：

- 传输加密和双向或服务端认证；
- 单连接速率、报文长度、字段数量和嵌套深度限制；
- 凭据通过环境变量或密钥服务注入，不写入仓库；
- 日志不输出完整 payload、密码、令牌或证书私钥；
- 连接数、认证失败、解析拒绝、发布成功/失败、在途数量、重连次数和处理延迟指标；
- 指标标签不使用 `deviceCode`、`msgId` 等高基数字段；
- 对设备时钟漂移进行观测，但不擅自把 `occurredAt` 改成平台接收时间。

## 10. 开发与联调顺序

1. 在管理端创建产品，定义设备表单、属性和事件物模型；
2. 建立外部设备标识到 `productKey + deviceCode` 的可信映射；
3. 实现协议连接、认证、解析和生命周期；
4. 构造标准消息并通过 `DeviceDataPublisher` 发布；
5. 启动 Kafka、MySQL、Redis、GreptimeDB、Admin、engine 和 rule-stream；
6. 先用少量设备验证建档、属性、事件、在线状态和规则输出；
7. 再使用目标消息大小、设备分布和规则复杂度进行阶梯压测。

可用下面的命令检查驱动模块能否完成编译：

```bash
cd backend
gradle :cn-service-driver:cn-driver-example:classes
```

完整链路、各服务职责见[架构指南](architecture-guide.md)，容量验证见[全链路压测指南](load-testing.md)。
