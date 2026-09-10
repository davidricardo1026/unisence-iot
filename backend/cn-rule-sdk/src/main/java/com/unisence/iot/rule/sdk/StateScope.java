package com.unisence.iot.rule.sdk;

/**
 * 窗口状态的聚合范围（{@code window_config.stateScope}）。
 *
 * <p>第一版只提供两档。曾设想的 GLOBAL 会把全平台流量压到单一 key，与「高频场景禁止直接使用」自相矛盾，
 * 需要全局聚合时按两阶段预聚合另行立项。
 */
public enum StateScope {

    /**
     * 默认。key = productKey.deviceCode，分区均匀且通常无需 repartition。
     */
    DEVICE,
    /**
     * key = productKey，会触发 repartition，超级产品可能形成热 key，必须显式选择。
     */
    PRODUCT;

    public boolean requiresRepartition() {
        return this == PRODUCT;
    }
}
