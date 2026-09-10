package com.unisence.iot.engine.config;

import java.util.Map;

public record EngineFieldCryptoProperties(String currentVersion, Map<String, String> keys) {

    public EngineFieldCryptoProperties {
        if (currentVersion == null || currentVersion.isBlank() || keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("app.engine.crypto.field.current-version/keys 不能为空");
        }
        keys = Map.copyOf(keys);
    }
}
