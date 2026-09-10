package com.unisence.iot.metadata;

import com.unisence.iot.rule.sdk.ThingModelSnapshot;

import java.util.Map;

/**
 * engine 的<b>不可变根快照</b>（metadata-sync-bus.md §6.1）。
 *
 * <p>整个进程只有一个 {@code AtomicReference<EngineMetadataSnapshot>}，收敛器构建好候选后
 * <b>一次 {@code set} 原子替换</b>。这是「跨域原子性」的全部实现：一个业务事务同时改了产品、
 * 设备和规则时，消息线程要么完整看到旧代际、要么完整看到新代际，不存在半新半旧的中间态。
 *
 * <p>因此严禁给任何单个域留独立的可变引用或 setter —— 那等于把原子替换退化成四次独立替换。
 *
 * <p><b>百万份 {@link DeviceRuntimeMeta} 不在根内</b>：根只持有紧凑的
 * {@link DeviceGenerationCatalog}（版本 + 存在性），完整投影在根外的有界缓存里按热点驻留。
 * 根外缓存可以并发变化，但它<b>没有自行判断新旧的权力</b> —— 版本不匹配一律等价于 miss，
 * 所以延迟淘汰、重复填充最多降低命中率，不可能让旧值越过新根。
 *
 * @param appliedHead             本快照已完整包含的提交水位
 * @param productsByKey           {@code productKey → 产品运行元数据}
 * @param productsById            {@code productId → 同一批对象}；设备投影按 productId 反查产品用
 * @param deviceCatalog           设备版本目录与存在性过滤器
 * @param thingModelsByProductKey 物模型快照
 * @param rules                   已编译规则索引
 */
public record EngineMetadataSnapshot(
    long appliedHead,
    Map<String, ProductRuntimeMeta> productsByKey,
    Map<Long, ProductRuntimeMeta> productsById,
    DeviceGenerationCatalog deviceCatalog,
    Map<String, ThingModelSnapshot> thingModelsByProductKey,
    RuleSnapshot rules) {

    public EngineMetadataSnapshot {
        if (appliedHead < 0) {
            throw new IllegalArgumentException("appliedHead 不能为负: " + appliedHead);
        }
        if (productsByKey == null || productsById == null || deviceCatalog == null
            || thingModelsByProductKey == null || rules == null) {
            throw new IllegalArgumentException("根快照的任何一个域都不允许为 null —— 宁可保留 LKG 也不安装残缺根");
        }
    }

    /**
     * 未定义该产品时返回 {@code null}；调用方须据此把消息投 DLQ，不得当作空产品放行。
     */
    public ProductRuntimeMeta product(String productKey) {
        return productsByKey.get(productKey);
    }

    public ProductRuntimeMeta productById(long productId) {
        return productsById.get(productId);
    }

    /**
     * 未定义该产品的物模型时返回 {@code null}，语义同上。
     */
    public ThingModelSnapshot thingModel(String productKey) {
        return thingModelsByProductKey.get(productKey);
    }
}
