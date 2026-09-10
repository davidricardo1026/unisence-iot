package com.unisence.iot.message;

/**
 * 设备维度消息：{@code productKey} / {@code deviceCode} 必非空，可直接定位到一台设备。
 *
 * <p>与 {@link ServiceHeartbeatMessage} 平级而非合并为一层，是为了让「有没有 deviceCode」
 * 成为编译期的类型差异，而不是每个消费方都要重复的运行期判断（契约 §2.1）。
 *
 * <p>两个字段同样<b>平铺</b>在各 record 上，不封装成设备标识记录（契约 §2.2）：
 * 消息是高频短命对象，少一层封装就少一次分配；代价是校验须由每个 record 显式调
 * {@link MessageFields#requireDevice}。
 */
public sealed interface DeviceMessage extends IotMessage
    permits DeviceCreateMessage, SequencedDeviceMessage, DeviceHeartbeatMessage {

    /**
     * 与 {@code us_iot_product.product_key char(6)} 同源。
     */
    int PRODUCT_KEY_CHARS = 6;
    /**
     * 与 {@code us_iot_device.device_code varchar(50)} 同源。
     */
    int MAX_DEVICE_CODE_CHARS = 50;

    /**
     * 线上键 {@code pk}；固定 6 位小写短码，不可变业务码 —— 消息模型不泄漏平台内部 ID。
     */
    String productKey();

    /**
     * 线上键 {@code dc}；产品内唯一，最长 50 字符，不可变。
     */
    String deviceCode();

    /**
     * Kafka 分区键 {@code productKey.deviceCode}。
     *
     * <p>四类设备消息共用同一个 key 是有意的：同一台设备的创建、属性、事件、心跳落进同一分区，
     * 消费侧才能保证「先创建后上报」的因果顺序。
     *
     * <p>与服务心跳的 {@code service.} 键空间<b>不可能碰撞</b>：本 key 第 7 个字符恒为 {@code '.'}
     * （productKey 定长 6），而 {@code "service"} 第 7 个字符是 {@code 'e'}。
     * <b>该不变量依赖 productKey 定长</b> —— 若将来改成变长，此处必须另加前缀区分。
     */
    @Override
    default String partitionKey() {
        return productKey() + '.' + deviceCode();
    }
}
