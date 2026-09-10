package com.unisence.iot.rule.sdk;

/**
 * 事件输入参数定义，对应 {@code us_iot_tm_event.input_params} 数组元素。
 *
 * <p>全部按 FIELD 落时序列；无 tag / time 角色。
 */
public record EventParamDefinition(String identifier, String name, PropertyDataType dataType) {
}
