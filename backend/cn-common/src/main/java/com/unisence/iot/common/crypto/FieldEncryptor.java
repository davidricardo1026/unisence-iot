package com.unisence.iot.common.crypto;

/**
 * 可逆字段加密：落库信封 {@code enc:&lt;version&gt;:&lt;base64&gt;}（AES-256-GCM）。
 */
public interface FieldEncryptor {

    /**
     * 使用 current-version 加密；返回 {@code enc:&lt;ver&gt;:&lt;base64&gt;}。
     * plaintext 为 null/blank 时抛 {@link IllegalArgumentException}。
     */
    String encrypt(String plaintext);

    /**
     * 解析信封并解密；非 {@code enc:} 前缀 → 抛 {@link IllegalArgumentException}。
     */
    String decrypt(String envelope);

    /**
     * 是否为密文信封（{@code startsWith("enc:")}）。
     */
    boolean isEncrypted(String value);

    /**
     * 若已是信封则 decrypt；否则原样返回。仅用于迁移/读旧数据，禁止用于新写入。
     */
    String decryptOrPassthrough(String value);
}
