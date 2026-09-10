package com.unisence.iot.rule.sdk;

/**
 * 物模型属性定义只读快照，对齐 {@code us_iot_tm_property}。
 *
 * <p>{@code dataType} 已由字符串码升级为 {@link PropertyDataType} 枚举 ——
 * 取值域已在 thing-model-design.md「data_type 取值域与存储映射」收敛，不再有不一致表述，
 * 第 3 层物模型校验依赖它做类型判定与时序表列路由。
 *
 * @param accessMode   1-只读, 2-读写
 */
public record PropertyDefinition(
    String identifier,
    String propertyName,
    PropertyDataType dataType,
    int accessMode,
    String unit,
    int retentionDays) {

    public PropertyDefinition {
        DataRetention.requireAllowed(retentionDays);
    }

    public boolean writable() {
        return accessMode == 2;
    }
}
