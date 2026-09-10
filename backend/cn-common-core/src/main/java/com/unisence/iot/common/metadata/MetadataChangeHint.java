package com.unisence.iot.common.metadata;

import java.util.Objects;

/**
 * Redis Pub/Sub 变更提示载荷（metadata-sync-bus.md §五）。
 *
 * <p><b>提示只携带目标水位，不携带业务值</b>：engine 收到后一律回权威源（MySQL）读取当前状态。
 * 这与 Nacos「通知配置身份、客户端重新查询内容」是同一个成熟模式。
 *
 * <p>Pub/Sub 是 at-most-once，丢失、重复、乱序都可能发生；正确性由 MySQL 提交水位 +
 * 变更目录 + engine 定时反熵保证，本提示只负责把发现延迟压到毫秒级。
 *
 * <p>线上 JSON 形如 {@code {"schemaVersion":1,"desiredHead":"43"}} ——
 * {@code desiredHead} 编码为<b>无前导零的十进制字符串</b>，因为 Lua 与 JavaScript 的双精度数
 * 超过 {@code 2^53} 后会丢精度，而水位是 {@code BIGINT}。
 */
public record MetadataChangeHint(int schemaVersion, long desiredHead) {

    /**
     * 当前唯一支持的载荷版本。收到未知版本必须拒绝解析并告警，不得猜测字段含义。
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private static final String FIELD_SCHEMA_VERSION = "\"schemaVersion\"";
    private static final String FIELD_DESIRED_HEAD = "\"desiredHead\"";

    public MetadataChangeHint {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion 必须为正数: " + schemaVersion);
        }
        if (desiredHead < 0) {
            throw new IllegalArgumentException("desiredHead 不能为负: " + desiredHead);
        }
    }

    public static MetadataChangeHint of(long desiredHead) {
        return new MetadataChangeHint(CURRENT_SCHEMA_VERSION, desiredHead);
    }

    public boolean isSupportedSchema() {
        return schemaVersion == CURRENT_SCHEMA_VERSION;
    }

    /**
     * 序列化为 §5.1 Lua 脚本产出的等价 JSON。
     *
     * <p>手写而非引入 JSON 库：{@code cn-common-core} 是被 engine（非 Spring）与 admin 共享的基座，
     * 载荷只有两个标量字段，为它增加序列化依赖不划算。
     */
    public String toJson() {
        return "{" + FIELD_SCHEMA_VERSION + ":" + schemaVersion
            + "," + FIELD_DESIRED_HEAD + ":\"" + desiredHead + "\"}";
    }

    /**
     * 解析 Pub/Sub 消息。
     *
     * <p>刻意做成宽松的字段扫描而非严格 JSON 解析：载荷结构由 §5.1 的 Lua 脚本固定产出，
     * 只有两个字段；这里唯一需要严格的是<b>水位必须按字符串读、按 long 解析</b>，
     * 绝不能经过 double。
     *
     * @throws IllegalArgumentException 结构非法或水位不是合法十进制正整数
     */
    public static MetadataChangeHint parse(String json) {
        Objects.requireNonNull(json, "提示载荷不能为空");
        int version = (int) readNumber(json, FIELD_SCHEMA_VERSION);
        long head = readNumber(json, FIELD_DESIRED_HEAD);
        return new MetadataChangeHint(version, head);
    }

    /**
     * 读取字段值；同时接受 {@code "43"} 与 {@code 43} 两种写法，但最终都按十进制整数解析。
     */
    private static long readNumber(String json, String field) {
        int at = json.indexOf(field);
        if (at < 0) {
            throw new IllegalArgumentException("提示载荷缺少字段 " + field + ": " + json);
        }
        int colon = json.indexOf(':', at + field.length());
        if (colon < 0) {
            throw new IllegalArgumentException("提示载荷字段 " + field + " 无值: " + json);
        }
        int i = colon + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) {
            i++;
        }
        int start = i;
        while (i < json.length() && json.charAt(i) >= '0' && json.charAt(i) <= '9') {
            i++;
        }
        if (start == i) {
            throw new IllegalArgumentException("提示载荷字段 " + field + " 不是十进制整数: " + json);
        }
        try {
            return Long.parseLong(json, start, i, 10);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("提示载荷字段 " + field + " 超出 long 范围: " + json, e);
        }
    }
}
