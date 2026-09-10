package com.unisence.iot.metadata;

import java.util.Set;

/**
 * engine 热路径需要的产品级元数据（metadata-sync-bus.md §6.1）。
 *
 * <p>只保留 engine <b>真正会读</b>的字段。产品描述、图标、厂商这些只在管理端展示的列不进来 ——
 * 它们进入根快照只会增加每次候选构建时的复制量，却永远不会被消息线程读到。
 *
 * @param productId           代理主键；设备唯一键 {@code (product_id, device_code)} 需要它
 * @param productKey          上行消息携带的产品编码
 * @param onlineTtlSeconds    在线租约秒数；不同产品上报频率差异极大，故按产品配置
 * @param deviceFormVersion   已发布的设备表单代际，设备投影双版本校验的第二个版本
 * @param ruleVisibleFormKeys 当前 active schema 中 {@code sensitive=false} 的字段 key
 */
public record ProductRuntimeMeta(
    long productId,
    String productKey,
    int onlineTtlSeconds,
    int deviceFormVersion,
    Set<String> ruleVisibleFormKeys) {

    public ProductRuntimeMeta {
        if (productKey == null || productKey.isEmpty()) {
            throw new IllegalArgumentException("productKey 不能为空: productId=" + productId);
        }
        if (onlineTtlSeconds <= 0) {
            throw new IllegalArgumentException(
                "onlineTtlSeconds 必须为正数: productKey=" + productKey + " ttl=" + onlineTtlSeconds);
        }
        if (deviceFormVersion < 0) {
            throw new IllegalArgumentException(
                "deviceFormVersion 不能为负: productKey=" + productKey + " version=" + deviceFormVersion);
        }
        // 只保留 key 名而不是整份 schema JSON：投影时只需要回答「这个 key 能不能给规则看」，
        // 而 schema 全文进内存要乘以产品数，且会诱使后续代码在热路径上重新解析 JSON
        ruleVisibleFormKeys = ruleVisibleFormKeys == null ? Set.of() : Set.copyOf(ruleVisibleFormKeys);
    }
}
