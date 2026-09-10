package com.unisence.iot.metadata;

/**
 * 设备投影的缓存信封（metadata-sync-bus.md §6.4）。
 *
 * <p>信封把「值」与「值属于哪一代」绑在一起。命中必须<b>同时</b>满足四个条件：
 *
 * <pre>
 * requestedRef == envelope.value.ref
 * catalog.version(deviceId) == envelope.deviceRowVersion
 * currentProduct.deviceFormVersion == envelope.deviceFormVersion
 * currentProduct.productId == envelope.value.productId
 * </pre>
 *
 * <p>任一条不满足立即失效，<b>不允许用 TTL 猜测新旧</b>。这条规则是整个多级缓存能成立的根基：
 * 只要校验通过就一定与当前根一致，因此 Caffeine 的淘汰、Redis 的丢写与乱序写、
 * 并发重复填充都最多降低命中率，不可能返回过期数据。
 *
 * <p>两个版本各管一件事：{@code deviceRowVersion} 捕获单台设备的静态字段变化；
 * {@code deviceFormVersion} 捕获产品表单换代对该产品下<b>全部</b>设备投影的影响 ——
 * 后者让「百万设备表单升级」退化成推进一个整数。
 *
 * @param schemaVersion     信封自身的编码版本；未知版本一律拒绝，不猜字段含义
 * @param deviceRowVersion  快照时的 {@code us_iot_device.version}
 * @param deviceFormVersion 快照时的 {@code us_iot_product.device_form_version}
 */
public record DeviceCacheEnvelope(
    int schemaVersion,
    int deviceRowVersion,
    int deviceFormVersion,
    DeviceRuntimeMeta value) {

    /**
     * 当前信封编码版本，同时也是 L2 value 的协议头。
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public DeviceCacheEnvelope {
        if (value == null) {
            throw new IllegalArgumentException("缓存值不能为空");
        }
    }

    public static DeviceCacheEnvelope of(int deviceRowVersion, int deviceFormVersion, DeviceRuntimeMeta value) {
        return new DeviceCacheEnvelope(CURRENT_SCHEMA_VERSION, deviceRowVersion, deviceFormVersion, value);
    }

    /**
     * 对当前根做双版本校验。
     *
     * @param ref  本次请求的设备标识；与信封内的 ref 不一致说明缓存键碰撞或写错桶
     * @param root 本批消息固定使用的根快照
     */
    public boolean isValidFor(DeviceRef ref, EngineMetadataSnapshot root) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            return false;
        }
        if (!value.ref().equals(ref)) {
            return false;
        }
        var currentRowVersion = root.deviceCatalog().version(value.deviceId());
        if (currentRowVersion.isEmpty() || currentRowVersion.getAsInt() != deviceRowVersion) {
            return false;
        }
        ProductRuntimeMeta product = root.product(ref.productKey());
        if (product == null) {
            return false;
        }
        // productId 也要比：同一个 productKey 允许标准产品与普通产品共存，
        // 且产品删除重建后 productKey 可能复用，只比表单版本会放过跨产品的陈旧条目
        return product.productId() == value.productId() && product.deviceFormVersion() == deviceFormVersion;
    }
}
