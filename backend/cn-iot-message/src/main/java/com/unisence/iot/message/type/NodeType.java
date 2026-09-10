package com.unisence.iot.message.type;

/**
 * 设备节点类型。取值与 {@code us_iot_device.node_type} / {@code us_iot_product.node_type} 的 tinyint 一致。
 *
 * <p>DDL 无 CHECK 约束（ddl-conventions.md §4），本枚举是该列取值域的唯一真相源。
 */
public enum NodeType {

    /**
     * 直连设备：直接接入平台，不挂网关。
     */
    DIRECT(1),
    /**
     * 网关：自身是设备，同时代理子设备。
     */
    GATEWAY(2),
    /**
     * 子设备：必须携带 gatewayCode 指明所属网关。
     */
    SUB_DEVICE(3);

    private final int code;

    NodeType(int code) {
        this.code = code;
    }

    /**
     * 落库与线上编码使用的 tinyint 值。
     */
    public int code() {
        return code;
    }

    /**
     * 仅子设备必须携带 gatewayCode；其余类型携带即为非法（契约 §七 错误码 4110）。
     */
    public boolean requiresGatewayCode() {
        return this == SUB_DEVICE;
    }

    public static NodeType fromCode(int code) {
        for (NodeType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的节点类型编码: " + code);
    }
}
