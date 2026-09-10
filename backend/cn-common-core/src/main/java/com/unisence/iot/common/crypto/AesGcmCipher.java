package com.unisence.iot.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 与运行框架无关的 AES-256-GCM 字段加密器。
 */
public final class AesGcmCipher {

    private static final String PREFIX = "enc:";
    private static final int IV_LENGTH = 12;
    private final String currentVersion;
    private final Map<String, String> encodedKeys;
    private final Map<String, SecretKey> keys = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(String currentVersion, Map<String, String> encodedKeys) {
        if (currentVersion == null || currentVersion.isBlank()) {
            throw new IllegalArgumentException("字段加密 current-version 不能为空");
        }
        if (encodedKeys == null || encodedKeys.isEmpty()) {
            throw new IllegalArgumentException("字段加密 keys 不能为空");
        }
        this.currentVersion = currentVersion;
        this.encodedKeys = Map.copyOf(encodedKeys);
        key(currentVersion);
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || plaintext.startsWith(PREFIX)) {
            throw new IllegalArgumentException("字段加密明文为空或格式非法");
        }
        byte[] iv = new byte[IV_LENGTH];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(currentVersion), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + currentVersion + ":" + Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("字段加密失败", error);
        }
    }

    public String decrypt(String envelope) {
        if (envelope == null || !envelope.startsWith(PREFIX)) {
            throw new IllegalArgumentException("不是字段加密信封");
        }
        String body = envelope.substring(PREFIX.length());
        int separator = body.indexOf(':');
        if (separator <= 0 || separator == body.length() - 1) {
            throw new IllegalArgumentException("字段加密信封格式非法");
        }
        String version = body.substring(0, separator);
        byte[] packed = Base64.getDecoder().decode(body.substring(separator + 1));
        if (packed.length <= IV_LENGTH) {
            throw new IllegalArgumentException("字段加密信封载荷非法");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(version),
                        new GCMParameterSpec(128, packed, 0, IV_LENGTH));
            return new String(cipher.doFinal(packed, IV_LENGTH, packed.length - IV_LENGTH),
                              StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("字段解密失败", error);
        }
    }

    private SecretKey key(String version) {
        return keys.computeIfAbsent(version, ignored -> {
            String encoded = encodedKeys.get(version);
            if (encoded == null || encoded.isBlank()) {
                throw new IllegalArgumentException("缺少字段加密密钥版本: " + version);
            }
            byte[] raw = Base64.getDecoder().decode(encoded);
            if (raw.length != 32) {
                throw new IllegalArgumentException("字段加密密钥必须为 32 字节: " + version);
            }
            return new SecretKeySpec(raw, "AES");
        });
    }
}
