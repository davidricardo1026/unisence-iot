package com.unisence.iot.timeseries;

import java.util.List;
import java.util.Map;

/**
 * Vendor-neutral connection settings plus driver-specific options.
 */
public record TimeSeriesConfig(
    String type,
    List<String> endpoints,
    String database,
    String user,
    String password,
    int poolMaxSize,
    int connectionTimeoutMs,
    Map<String, String> options) {

    public TimeSeriesConfig {
        if (type == null || type.isBlank()) throw new IllegalArgumentException("时序数据库 type 不可为空");
        if (endpoints == null || endpoints.isEmpty())
            throw new IllegalArgumentException("时序数据库 endpoints 不可为空");
        if (endpoints.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("时序数据库 endpoints 不可包含空值");
        }
        if (database == null || database.isBlank()) throw new IllegalArgumentException("时序数据库 database 不可为空");
        if (poolMaxSize <= 0) throw new IllegalArgumentException("时序数据库 poolMaxSize 必须为正数");
        if (connectionTimeoutMs <= 0) throw new IllegalArgumentException("时序数据库 connectionTimeoutMs 必须为正数");
        type = type.trim().toLowerCase();
        endpoints = List.copyOf(endpoints);
        user = user == null ? "" : user;
        password = password == null ? "" : password;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    public String option(String key, String defaultValue) {
        return options.getOrDefault(key, defaultValue);
    }

    public int intOption(String key, int defaultValue) {
        return Integer.parseInt(option(key, Integer.toString(defaultValue)));
    }

    public long longOption(String key, long defaultValue) {
        return Long.parseLong(option(key, Long.toString(defaultValue)));
    }

    public boolean booleanOption(String key, boolean defaultValue) {
        return Boolean.parseBoolean(option(key, Boolean.toString(defaultValue)));
    }

    @Override
    public String toString() {
        return "TimeSeriesConfig[type=" + type + ", endpoints=" + endpoints + ", database=" + database
            + ", user=" + user + ", poolMaxSize=" + poolMaxSize + ", connectionTimeoutMs="
            + connectionTimeoutMs + "]";
    }
}
