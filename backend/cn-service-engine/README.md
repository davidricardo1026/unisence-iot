# cn-service-engine

数据面摄入服务（Vert.x 5 / Java 21）。本模块不提供 HTTP 接口。

职责：消费 Kafka 上行消息，完成设备准入、物模型校验、在线租约、GreptimeDB 写入，并在时序库确认后按透传路由转发到已登记
Topic。规则计算由独立的规则运行时进程负责，不在本模块内执行。

已交付：

- `iot.raw.data` / `iot.event` 消费、MessagePack 解码、物模型校验；
- 属性 / 事件 / 上下线写入 GreptimeDB；
- 元数据同步、设备在线状态、设备自动建档；
- 落库确认后的透传路由（`purpose=ROUTE`）；
- 入口指标（JMX）。

本地启动：

```bash
cd backend
gradle :cn-service-engine:run
```

生产或压测启动时，JVM 参数须包含 `-Dgreptimedb.use_os_signal=false`。

架构、Topic 约定和扩容原则见 [架构指南](../../docs/architecture-guide.md)
。压测步骤见 [全链路压测指南](../../docs/load-testing.md)。
