package com.unisence.iot.common.crypto;

/**
 * Spring 配置适配器；加解密实现由零框架依赖的 {@link AesGcmCipher} 统一提供。
 */
public class AesGcmFieldEncryptor implements FieldEncryptor {

    private final AesGcmCipher cipher;

    public AesGcmFieldEncryptor(FieldCryptoProperties properties) {
        this.cipher = new AesGcmCipher(properties.getCurrentVersion(), properties.getKeys());
    }

    @Override
    public String encrypt(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    @Override
    public String decrypt(String envelope) {
        return cipher.decrypt(envelope);
    }

    @Override
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith("enc:");
    }

    @Override
    public String decryptOrPassthrough(String value) {
        return isEncrypted(value) ? decrypt(value) : value;
    }
}
