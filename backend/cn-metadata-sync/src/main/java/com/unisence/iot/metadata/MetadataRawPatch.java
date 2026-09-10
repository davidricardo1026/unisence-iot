package com.unisence.iot.metadata;

import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.config.RuleDefinition;
import com.unisence.iot.rule.sdk.ThingModelSnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * loader 在一致性快照内读出的<b>原始</b>数据（metadata-sync-bus.md §13.3）。
 *
 * <p>「原始」是关键约束：loader 只负责查询与结构化，<b>不做</b>跨域校验、不编译 Groovy、
 * 不触碰当前根快照。所有拼装、复制与一次性安装统一交给 {@code MetadataSnapshotBuilder}。
 * 这样才能保证「事务内不编译脚本」（编译可能耗时数百毫秒，占着 read view 不放）
 * 与「构建失败时整个候选根都不安装」这两条同时成立。
 *
 * <p>密封层次而不是 {@code Object}：新增元数据域时编译器会强制每个 switch 都处理它。
 */
public sealed interface MetadataRawPatch
    permits MetadataRawPatch.ProductRawPatch,
    MetadataRawPatch.DeviceGenerationRawPatch,
    MetadataRawPatch.ThingModelRawPatch,
    MetadataRawPatch.RuleRawPatch {

    MetaKeyEnum metaKey();

    /**
     * 本次请求的 scopeIds；含 {@code 0} 表示全域。
     */
    Set<Long> requestedScopeIds();

    /**
     * 请求为全域重建（{@code scope_id=0}），增量结果应整体替换而非合并。
     */
    default boolean isFullDomain() {
        return requestedScopeIds().contains(0L);
    }

    /**
     * 产品域。
     *
     * @param products 本次读到的存活产品
     * @param missing  请求了但已不存在（删除或逻辑删）的 productId，需要从候选根移除
     */
    record ProductRawPatch(
        Set<Long> requestedScopeIds,
        List<ProductRuntimeMeta> products,
        Set<Long> missing) implements MetadataRawPatch {

        @Override
        public MetaKeyEnum metaKey() {
            return MetaKeyEnum.IOT_PRODUCT;
        }
    }

    /**
     * 设备域。
     *
     * <p>增量只携带 {@code deviceId → rowVersion} 与新增设备的 ref —— <b>不携带完整投影</b>。
     * 全域重建时 {@code fullCatalog} 直接给出流式构建好的目录，同样不产生百万 DTO。
     *
     * @param upserts     存活设备的版本
     * @param removals    已删除设备
     * @param additions   新增设备的 ref，用于补进 Bloom delta 以杜绝假阴性
     * @param fullCatalog 全域重建时的完整目录；增量时为 {@code null}
     */
    record DeviceGenerationRawPatch(
        Set<Long> requestedScopeIds,
        Map<Long, Integer> upserts,
        Set<Long> removals,
        List<DeviceRef> additions,
        ShardedDeviceCatalog fullCatalog) implements MetadataRawPatch {

        @Override
        public MetaKeyEnum metaKey() {
            return MetaKeyEnum.IOT_DEVICE;
        }
    }

    /**
     * 物模型域。
     *
     * @param snapshots 受影响产品的完整物模型；一次查齐属性、事件、服务后整体替换该条目
     * @param missing   已无物模型（产品删除或定义清空）的 productId
     */
    record ThingModelRawPatch(
        Set<Long> requestedScopeIds,
        Map<Long, ThingModelSnapshot> snapshots,
        Set<Long> missing) implements MetadataRawPatch {

        @Override
        public MetaKeyEnum metaKey() {
            return MetaKeyEnum.IOT_THING_MODEL;
        }
    }

    /**
     * 规则域。
     *
     * <p>脚本以<b>原文</b>返回，编译在事务外进行。
     *
     * @param rules   受影响规则的原始行；即时与窗口两类混在一起，由 {@code definition} 的
     *                运行时类型区分
     * @param missing 已删除或已停用的规则<b>键</b>（{@code I:12} / {@code W:12} / {@code R:12}），
     *                需要从候选根移除。
     *                <p><b>必须是键而不是 ruleId</b>：三张规则表各有独立的
     *                {@code AUTO_INCREMENT}，变更日志里的 scope_id 只是一个数字，
     *                加载时三张表都要查。若即时规则 12 号被删而窗口规则 12 号仍在，
     *                按 id 判定会得出「12 号还在」，那条即时规则将永远留在候选根里继续执行
     * @param routes  受影响的透传路由原始行；不进 {@link RuleRow}，独立装配
     */
    record RuleRawPatch(
        Set<Long> requestedScopeIds,
        List<RuleRow> rules,
        Set<String> missing,
        List<RouteRuleRow> routes) implements MetadataRawPatch {

        @Override
        public MetaKeyEnum metaKey() {
            return MetaKeyEnum.IOT_RULES;
        }
    }

    /**
     * 一条规则的原始行 + 绑定 + 各档位脚本，脚本尚未编译。
     *
     * @param filterScript 规则级前置过滤脚本原文
     * @param levelScripts 与 {@code definition.levels()} <b>一一对应且同序</b>的脚本原文。
     *                     顺序由 {@link RuleDefinition#levels()} 的 severity 升序决定，
     *                     装配方必须按同一顺序产出 —— 错位会让「危急档」执行「预警档」的输出脚本，
     *                     而两者都能跑通，不会有任何异常
     * @param levelTargets 档位编码 → 该档位绑定的 Kafka 输出，列表按 {@code outputId ASC}
     */
    record RuleRow(
        RuleDefinition definition,
        long revision,
        String scriptSha256,
        String filterScript,
        List<LevelScripts> levelScripts,
        Set<Long> productIds,
        Map<String, List<KafkaOutputTarget>> levelTargets) {

        public RuleRow {
            if (levelTargets == null || levelTargets.isEmpty()) {
                levelTargets = Map.of();
            } else {
                Map<String, List<KafkaOutputTarget>> frozen = new HashMap<>(levelTargets.size());
                for (var entry : levelTargets.entrySet()) {
                    frozen.put(entry.getKey(),
                               entry.getValue() == null ? List.of() : List.copyOf(entry.getValue()));
                }
                levelTargets = Map.copyOf(frozen);
            }
        }
    }

    /**
     * 一条透传路由规则的原始行（无脚本、无档位）。
     *
     * @param targets 绑定的 Kafka 输出，按 {@code outputId ASC}
     */
    record RouteRuleRow(
        long ruleId,
        String ruleCode,
        MessageType messageType,
        Set<Long> productIds,
        List<KafkaOutputTarget> targets) {

        public RouteRuleRow {
            productIds = productIds == null ? Set.of() : Set.copyOf(productIds);
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    /**
     * 一个档位的脚本原文。
     *
     * @param conditionScript {@code conditionKind=SCRIPT} 时非空
     */
    record LevelScripts(String conditionScript, String outputScript) {
    }
}
