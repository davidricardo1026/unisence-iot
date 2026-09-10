package com.unisence.iot.common.crypto;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * 绑定 {@code app.crypto.field.*}。
 */
@Data
public class FieldCryptoProperties {

    /**
     * 新加密使用的版本号，默认 v1
     */
    private String currentVersion = "v1";

    /**
     * version → Base64 编码的 32 字节密钥
     */
    private Map<String, String> keys = new HashMap<>();
}
