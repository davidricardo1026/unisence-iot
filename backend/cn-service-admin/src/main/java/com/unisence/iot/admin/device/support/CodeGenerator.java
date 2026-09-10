package com.unisence.iot.admin.device.support;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class CodeGenerator {

    private static final char[] PRODUCT_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final char[] PRODUCT_FIRST_ALPHABET = "abcdefghjkmnpqrstuvwxyz".toCharArray();
    private final SecureRandom random = new SecureRandom();

    /**
     * 小型档 product_key：6 位小写无易混字符，首位必须为字母。
     *
     * <p>产品标识由产品上下文区分，无需固定前缀；全小写与数据库的大小写不敏感唯一约束保持一致。</p>
     */
    public String nextProductKey() {
        return randomString(PRODUCT_FIRST_ALPHABET, 1) + randomString(PRODUCT_ALPHABET, 5);
    }

    public String nextDeviceCode() {
        return "d" + randomString(PRODUCT_ALPHABET, 8);
    }

    private String randomString(char[] alphabet, int len) {
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(buf);
    }
}
