package com.unisence.iot.metadata;

import io.vertx.sqlclient.Tuple;

import java.util.Collection;

/**
 * {@code IN (?, ?, …)} 占位符的构造工具。
 *
 * <p>参数一律走绑定变量而不是字符串拼接：既避免注入，也让驱动能复用预编译语句。
 *
 * <p>代价是<b>SQL 文本随批大小变化</b>，会持续冲刷预编译缓存。因此所有批量读取都必须
 * 定长分批（见各 loader 的 batchSize），让实际出现的 SQL 形状收敛到少数几种。
 */
final class SqlIn {

    private SqlIn() {
    }

    /**
     * 把模板中的 {@code %s} 替换成 {@code n} 个逗号分隔的 {@code ?}。
     */
    static String expand(String template, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("IN 列表不能为空");
        }
        StringBuilder placeholders = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                placeholders.append(',');
            }
            placeholders.append('?');
        }
        return String.format(template, placeholders);
    }

    static Tuple tuple(Collection<Long> values) {
        Tuple tuple = Tuple.tuple();
        for (Long value : values) {
            tuple.addLong(value);
        }
        return tuple;
    }

    /**
     * 前置若干标量参数，再追加 IN 列表。
     */
    static Tuple tuple(Object first, Collection<?> values) {
        Tuple tuple = Tuple.tuple();
        tuple.addValue(first);
        for (Object value : values) {
            tuple.addValue(value);
        }
        return tuple;
    }
}
