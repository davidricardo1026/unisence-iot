package com.unisence.iot.rule.sdk;

/**
 * 产品只读快照，对齐 {@code us_iot_product}。
 *
 * <p>只暴露规则判定需要的列；attributes、device_form_schema 与审计字段不进入脚本可见范围。
 *
 * @param productType 1-普通产品, 2-标准产品；规则只允许绑定 1
 * @param nodeType    1-直连设备, 2-网关, 3-子设备
 */
public record ProductSnapshot(
    long productId,
    String productKey,
    String productName,
    int productType,
    int nodeType,
    String vendor,
    String model) {

    public boolean isGateway() {
        return nodeType == 2;
    }
}
