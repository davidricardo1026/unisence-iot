package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.rule.compiler.CompiledRuleScript;
import com.unisence.iot.rule.compiler.RuleScriptCompiler;
import com.unisence.iot.rule.config.ConditionKind;
import com.unisence.iot.rule.config.InstantRuleDefinition;
import com.unisence.iot.rule.config.LevelDefinition;
import com.unisence.iot.rule.config.RuleKind;
import com.unisence.iot.rule.config.WindowRuleDefinition;
import com.unisence.iot.rule.sdk.RuleFilter;
import com.unisence.iot.rule.sdk.ThingModelSnapshot;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 由「当前根 + 本轮原始数据」构建候选根（metadata-sync-bus.md §7.3）。
 *
 * <p>三条不可动摇的规则：
 * <ol>
 *   <li><b>浅拷贝 + 结构共享</b>：只复制受影响的 map 条目，未变化的对象直接传引用。
 *       百万设备下深拷贝整个根意味着每次收敛都要复制几百 MB。</li>
 *   <li><b>Groovy 编译只在这里、且在事务外</b>：loader 已经把只读事务关掉了。</li>
 *   <li><b>全成功才返回</b>：任何一步抛异常，调用方保留 LKG，绝不安装残缺根。</li>
 * </ol>
 *
 * <p>构建顺序固定为<b>产品 → 设备版本目录 → 物模型 → 规则与跨域索引</b>：
 * 设备目录要用产品映射解析 productKey，规则索引要用产品映射把 productId 翻成 productKey，
 * 顺序颠倒会读到上一代的产品。
 */
@Slf4j
public final class MetadataSnapshotBuilder {

    private final RuleScriptCompiler compiler;

    public MetadataSnapshotBuilder(RuleScriptCompiler compiler) {
        this.compiler = compiler;
    }

    /**
     * @param current     当前根（LKG）；启动引导时传入空根
     * @param patches     本轮各域的原始数据，可能只覆盖部分域
     * @param appliedHead 候选根的水位，取本轮一致性快照可见的 {@code committedHead}
     */
    public EngineMetadataSnapshot build(EngineMetadataSnapshot current,
                                        List<MetadataRawPatch> patches,
                                        long appliedHead) {
        Map<MetaKeyEnum, MetadataRawPatch> byDomain = new HashMap<>();
        for (MetadataRawPatch patch : patches) {
            byDomain.put(patch.metaKey(), patch);
        }

        Map<String, ProductRuntimeMeta> productsByKey = new HashMap<>(current.productsByKey());
        Map<Long, ProductRuntimeMeta> productsById = new HashMap<>(current.productsById());
        applyProducts(byDomain.get(MetaKeyEnum.IOT_PRODUCT), productsByKey, productsById);

        DeviceGenerationCatalog catalog = applyDevices(
            byDomain.get(MetaKeyEnum.IOT_DEVICE), current.deviceCatalog());

        Map<String, ThingModelSnapshot> thingModels = applyThingModels(
            byDomain.get(MetaKeyEnum.IOT_THING_MODEL), current, productsById);

        RuleSnapshot rules = applyRules(byDomain.get(MetaKeyEnum.IOT_RULES), current.rules(), productsById);

        return new EngineMetadataSnapshot(
            appliedHead,
            Map.copyOf(productsByKey),
            Map.copyOf(productsById),
            catalog,
            Map.copyOf(thingModels),
            rules);
    }

    /**
     * 只算「候选产品的 {@code productId → 产品} 映射」，供收敛器在读事务内提前发布给
     * 设备与规则 loader 使用（构建顺序：产品 → 设备 → 物模型 → 规则）。
     *
     * <p>与 {@link #build} 内部走同一段合并逻辑，因此两处结果必然一致 ——
     * 分成两份实现迟早会漂移成「loader 看到的产品」与「候选根里的产品」不是同一批。
     */
    public static Map<Long, ProductRuntimeMeta> mergeProductsById(
        Map<Long, ProductRuntimeMeta> current, MetadataRawPatch patch) {
        Map<String, ProductRuntimeMeta> byKey = new HashMap<>();
        Map<Long, ProductRuntimeMeta> byId = new HashMap<>(current);
        for (ProductRuntimeMeta product : current.values()) {
            byKey.put(product.productKey(), product);
        }
        applyProducts(patch, byKey, byId);
        return byId;
    }

    private static void applyProducts(MetadataRawPatch patch,
                                      Map<String, ProductRuntimeMeta> byKey,
                                      Map<Long, ProductRuntimeMeta> byId) {
        if (!(patch instanceof MetadataRawPatch.ProductRawPatch products)) {
            return;
        }
        if (products.isFullDomain()) {
            byKey.clear();
            byId.clear();
        }
        for (Long missing : products.missing()) {
            ProductRuntimeMeta removed = byId.remove(missing);
            if (removed != null) {
                // 按 key 移除必须比对 productId：productKey 允许在标准/普通产品间复用，
                // 直接 byKey.remove(key) 可能误删另一条仍然有效的产品
                byKey.computeIfPresent(removed.productKey(),
                                       (key, existing) -> existing.productId() == missing ? null : existing);
            }
        }
        for (ProductRuntimeMeta product : products.products()) {
            ProductRuntimeMeta previous = byId.put(product.productId(), product);
            if (previous != null && !previous.productKey().equals(product.productKey())) {
                // productKey 创建后不可变，走到这里说明 ID 复用或数据被直改；清掉旧键避免残留
                byKey.remove(previous.productKey());
            }
            byKey.put(product.productKey(), product);
        }
    }

    private DeviceGenerationCatalog applyDevices(MetadataRawPatch patch, DeviceGenerationCatalog current) {
        if (!(patch instanceof MetadataRawPatch.DeviceGenerationRawPatch devices)) {
            return current;
        }
        if (devices.fullCatalog() != null) {
            return devices.fullCatalog();
        }
        if (!(current instanceof ShardedDeviceCatalog sharded)) {
            throw new IllegalStateException("当前设备目录不是分片目录，无法增量合并: " + current.getClass());
        }
        return sharded.withChanges(devices.upserts(), devices.removals(), devices.additions());
    }

    private Map<String, ThingModelSnapshot> applyThingModels(MetadataRawPatch patch,
                                                             EngineMetadataSnapshot current,
                                                             Map<Long, ProductRuntimeMeta> productsById) {
        if (!(patch instanceof MetadataRawPatch.ThingModelRawPatch thingModels)) {
            return new HashMap<>(current.thingModelsByProductKey());
        }
        Map<String, ThingModelSnapshot> result = thingModels.isFullDomain()
            ? new HashMap<>()
            : new HashMap<>(current.thingModelsByProductKey());

        for (Long productId : thingModels.missing()) {
            ProductRuntimeMeta product = productsById.get(productId);
            if (product != null) {
                result.remove(product.productKey());
            }
        }
        for (var entry : thingModels.snapshots().entrySet()) {
            ProductRuntimeMeta product = productsById.get(entry.getKey());
            if (product == null) {
                // 物模型存在但产品已删：条目留下也永远不会被命中（消息先按 productKey 找产品）
                continue;
            }
            result.put(product.productKey(), entry.getValue());
        }
        return result;
    }

    /**
     * 应用规则变化并重建索引。
     *
     * <p>编译在这里发生。缓存键 {@code ruleId:revision:scriptSha256} 让「脚本没变就不重编译」
     * 成立：一秒内保存 20 次同一条规则，最终只会编译<b>最新那一版</b>一次 ——
     * 因为收敛器已经按 scope 去重，只读到该规则的当前 revision。
     */
    private RuleSnapshot applyRules(MetadataRawPatch patch,
                                    RuleSnapshot current,
                                    Map<Long, ProductRuntimeMeta> productsById) {
        if (!(patch instanceof MetadataRawPatch.RuleRawPatch rules)) {
            // 产品变化也会影响规则索引（productKey → 规则），因此即便规则本身没变也要重建索引
            return RuleSnapshot.of(current.byKey(), productsById, current.routesById());
        }
        Map<String, CompiledRule> byKey = rules.isFullDomain()
            ? new HashMap<>()
            : new HashMap<>(current.byKey());
        Map<Long, CompiledRouteRule> routesById = rules.isFullDomain()
            ? new HashMap<>()
            : new HashMap<>(current.routesById());

        String routePrefix = RuleKind.ROUTE.prefix() + ":";
        for (String missing : rules.missing()) {
            byKey.remove(missing);
            if (missing.startsWith(routePrefix)) {
                routesById.remove(Long.parseLong(missing.substring(routePrefix.length())));
            }
        }
        List<String> compiled = new ArrayList<>();
        for (MetadataRawPatch.RuleRow row : rules.rules()) {
            CompiledRule previous = byKey.get(row.definition().ruleKey());
            CompiledRule rule = reuseOrCompile(previous, row);
            byKey.put(rule.ruleKey(), rule);
            if (previous != rule) {
                compiled.add(rule.cacheKey());
            }
        }
        if (!compiled.isEmpty()) {
            log.info("规则编译完成: 条数={} keys={}", compiled.size(), compiled);
        }
        List<MetadataRawPatch.RouteRuleRow> routeRows = new ArrayList<>(rules.routes());
        routeRows.sort(Comparator.comparingLong(MetadataRawPatch.RouteRuleRow::ruleId));
        for (MetadataRawPatch.RouteRuleRow row : routeRows) {
            routesById.put(row.ruleId(), new CompiledRouteRule(
                row.ruleId(), row.ruleCode(), row.messageType(), row.productIds(), row.targets()));
        }
        return RuleSnapshot.of(byKey, productsById, routesById);
    }

    /**
     * revision 与脚本摘要都没变时直接复用上一代编译产物。
     *
     * <p>只改了产品绑定却重新编译一遍 Groovy，是纯粹的浪费；而且每次编译都会生成新的
     * {@code Class} 对象，频繁重编译会让 Metaspace 持续增长。
     *
     * <p>复用的前提是<b>档位数量与顺序也没变</b>。这一点由 {@code scriptSha256} 保证 ——
     * 它是「filter + 按 severity 升序逐档的 condition 与 output」拼接后的摘要，
     * 增删档位或调换 severity 都会改变拼接顺序，从而改变摘要。
     */
    private CompiledRule reuseOrCompile(CompiledRule previous, MetadataRawPatch.RuleRow row) {
        boolean reusable = previous != null
            && previous.revision() == row.revision()
            && previous.scriptSha256().equals(row.scriptSha256())
            && previous.levels().size() == row.levelScripts().size();
        if (reusable) {
            return assemble(row, previous.filter(), previous.levels());
        }
        String cacheKey = row.definition().ruleKey() + ":" + row.revision() + ":" + row.scriptSha256();
        var filter = compiler.compileFilter(cacheKey, row.filterScript());
        List<CompiledLevel> levels = new ArrayList<>(row.levelScripts().size());
        List<LevelDefinition> definitions = row.definition().levels();
        for (int i = 0; i < definitions.size(); i++) {
            LevelDefinition level = definitions.get(i);
            MetadataRawPatch.LevelScripts scripts = row.levelScripts().get(i);
            String levelCacheKey = cacheKey + ":L" + level.levelId();
            List<KafkaOutputTarget> targets = row.levelTargets().get(level.levelCode());
            if (targets == null || targets.isEmpty()) {
                throw new IllegalStateException(
                    "档位未绑定 Kafka 输出: " + row.definition().ruleKey() + "/" + level.levelCode());
            }
            levels.add(new CompiledLevel(
                level,
                level.conditionKind() == ConditionKind.SCRIPT
                    ? compiler.compileCondition(levelCacheKey, scripts.conditionScript())
                    : null,
                compiler.compileOutput(levelCacheKey, scripts.outputScript()),
                targets,
                LevelHeaderBytes.of(
                    row.definition().kind(),
                    row.definition().ruleId(),
                    row.revision(),
                    row.definition().ruleCode(),
                    level)));
        }
        return assemble(row, filter, levels);
    }

    /**
     * 按规则类别装配编译产物。
     *
     * <p>{@code switch} 覆盖两个分支由编译器保证：新增第三类规则时这里报错，
     * 而不是运行期落进一个 {@code default} 分支被当成即时规则跑掉。
     */
    private static CompiledRule assemble(MetadataRawPatch.RuleRow row,
                                         CompiledRuleScript<RuleFilter> filter,
                                         List<CompiledLevel> levels) {
        return switch (row.definition()) {
            case InstantRuleDefinition instant -> new CompiledInstantRule(
                instant, row.revision(), row.scriptSha256(), row.productIds(), filter, levels);
            case WindowRuleDefinition window -> new CompiledWindowRule(
                window, row.revision(), row.scriptSha256(), row.productIds(), filter, levels);
        };
    }
}
