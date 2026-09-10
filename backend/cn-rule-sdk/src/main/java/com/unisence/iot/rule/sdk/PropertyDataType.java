package com.unisence.iot.rule.sdk;

/**
 * 物模型属性数据类型（{@code us_iot_tm_property.data_type} 取值域的<b>全仓唯一定义</b>）。
 *
 * <p>真相源与存储映射见 device-module/thing-model-design.md「data_type 取值域与存储映射」。
 * 落位在 {@code cn-rule-sdk} 而非 device-module 的某个 Spring 模块，是因为它同时被
 * engine（第 3 层校验与落盘）、admin（保存物模型时校验）和 Groovy 脚本使用，必须在零依赖模块里。
 *
 * <p>此前该取值域只以散文形式散落在多份文档且彼此不一致，第 3 层校验因此无法实现；本枚举即收敛结果。
 */
public enum PropertyDataType {

    BOOL("bool", 1),
    INT("int", 2),
    FLOAT("float", 3),
    DOUBLE("double", 3),
    STRING("string", 4),
    TEXT("text", 4),
    ENUM("enum", 4),
    IMAGE("image", 4);

    /**
     * 属性时序物理表类型：1-bool / 2-long / 3-double / 4-text。
     */
    public static final int VALUE_TYPE_BOOL = 1;
    public static final int VALUE_TYPE_LONG = 2;
    public static final int VALUE_TYPE_DOUBLE = 3;
    public static final int VALUE_TYPE_TEXT = 4;

    private final String code;
    private final int valueType;

    PropertyDataType(String code, int valueType) {
        this.code = code;
        this.valueType = valueType;
    }

    /**
     * 落库与契约中使用的编码，不是 {@link #name()}。
     */
    public String code() {
        return code;
    }

    /**
     * 该类型在时序表中占用的 {@code value_type} 与对应 {@code value_*} 列。
     */
    public int valueType() {
        return valueType;
    }

    public static PropertyDataType fromCode(String code) {
        for (PropertyDataType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的物模型数据类型编码: " + code);
    }

    /**
     * 判断一个 MessagePack 解码后的值是否符合本类型。
     *
     * <p>{@code float} 刻意接受整数族：设备上报 {@code 25} 而非 {@code 25.0} 极其常见，
     * MessagePack 会解码成整数，拒绝它只会制造大量无意义的 DLQ。
     * 反向<b>不成立</b> —— {@code int} 不接受浮点，那是真实的精度语义错误。
     */
    public boolean accepts(Object value) {
        if (value == null) {
            return false;
        }
        return switch (this) {
            case BOOL -> value instanceof Boolean;
            case INT -> isIntegral(value);
            case FLOAT, DOUBLE -> value instanceof Float || value instanceof Double || isIntegral(value);
            case STRING, TEXT, ENUM, IMAGE -> value instanceof String;
        };
    }

    /**
     * 是否可参与数值聚合（SUM / AVG / MIN / MAX / CHANGE_RATE）。
     *
     * <p>{@code date} 虽然底层是整数，但**不算数值**：对时间戳求平均没有业务含义。
     */
    public boolean numeric() {
        return this == INT || this == FLOAT || this == DOUBLE;
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long;
    }
}
