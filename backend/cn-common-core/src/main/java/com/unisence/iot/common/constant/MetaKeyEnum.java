package com.unisence.iot.common.constant;

/**
 * 元数据域标识（metadata-sync-bus.md §四）。
 *
 * <p>取值域固定为四个聚合根域，是 {@code us_sys_metadata_change.meta_key} 的唯一真相源。
 * 每个域的 {@code scope_id} 含义不同：
 * <ul>
 *   <li>{@link #IOT_PRODUCT} / {@link #IOT_THING_MODEL} — 普通产品 {@code product_id}</li>
 *   <li>{@link #IOT_DEVICE} — {@code device_id}</li>
 *   <li>{@link #IOT_RULES} — {@code rule_id}</li>
 * </ul>
 *
 * <p>{@code scope_id = 0} 在任一域都表示「该域全量强制重建」，只允许运维强制重建与超阈值批处理产生。
 *
 * <p>旧占位值 {@code DEVICE_BLACKLIST} 已删除：它没有对应的业务契约、数据库表、接口或执行逻辑，
 * 禁止仅因枚举中曾存在该值就推导出「设备黑名单」业务。
 */
public enum MetaKeyEnum {

    /**
     * 产品基本字段、online_ttl_seconds、device_form_version 代际。
     */
    IOT_PRODUCT,

    /**
     * 单台设备静态投影：名称、网关、节点类型、位置、device_form_data。
     */
    IOT_DEVICE,

    /** 产品物模型：属性 / 事件 / 服务定义。 */
    IOT_THING_MODEL,

    /**
     * 规则定义、启停、脚本、配置与产品绑定。
     */
    IOT_RULES;

    /** 落库值与枚举名一致，避免两套命名。 */
    public String getKey() {
        return name();
    }

    /**
     * 解析落库值。
     *
     * @throws IllegalArgumentException 取值域外 —— 调用方（engine）必须据此转 DEGRADED 并拒绝越过该水位，
     *                                  不得当作「无变化」跳过（metadata-sync-bus.md §十一）
     */
    public static MetaKeyEnum fromKey(String key) {
        if (key == null) {
            throw new IllegalArgumentException("meta_key 不能为空");
        }
        return valueOf(key);
    }
}
