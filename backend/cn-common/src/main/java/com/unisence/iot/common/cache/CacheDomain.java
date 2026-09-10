package com.unisence.iot.common.cache;

public enum CacheDomain {
    USER("user", "display-user"),
    USER_CODE("user-code", "display-user-code"),
    DEPT("dept", "display-dept"),
    ROLE("role", "display-role");

    private final String cacheName;
    private final String caffeineCacheName;

    CacheDomain(String cacheName, String caffeineCacheName) {
        this.cacheName = cacheName;
        this.caffeineCacheName = caffeineCacheName;
    }

    public String cacheName() {
        return cacheName;
    }

    public String caffeineCacheName() {
        return caffeineCacheName;
    }

    public static CacheDomain fromCacheName(String cacheName) {
        for (CacheDomain d : values()) {
            if (d.cacheName.equals(cacheName)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown cacheName: " + cacheName);
    }
}
