package com.unisence.iot.metadata;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Row;

/**
 * Vert.x SQL Client 的 JSON 列读取适配。
 *
 * <p>Vert.x 5/MySQL 会把 JSON 列解码为 {@link JsonObject}/{@link JsonArray}，
 * 不能再假设 {@code Row#getString}。统一在仓储边界转成 JSON 文本，避免各 loader 分叉。
 */
final class SqlJson {

    private SqlJson() {
    }

    static String text(Row row, String column) {
        Object value = row.getValue(column);
        return switch (value) {
            case null -> null;
            case String text -> text;
            case JsonObject object -> object.encode();
            case JsonArray array -> array.encode();
            default -> Json.encode(value);
        };
    }
}
