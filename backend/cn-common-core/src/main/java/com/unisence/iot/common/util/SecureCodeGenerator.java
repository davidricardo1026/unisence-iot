package com.unisence.iot.common.util;

import java.security.SecureRandom;

/**
 * SecureRandom 码生成：product_key / device_code 兜底。
 */
public final class SecureCodeGenerator {

    /**
     * 无易混字符字母表（排除 0 o 1 l i）
     */
    private static final char[] SAFE_ALPHANUM =
        "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private static final char[] SAFE_LETTERS =
        "abcdefghjkmnpqrstuvwxyz".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecureCodeGenerator() {
    }

    /**
     * 6 位 product_key：全小写无易混字符，首位必须为字母。
     * 产品上下文本身已区分实体，因此不使用固定前缀。
     */
    public static String productKey() {
        return randomFrom(SAFE_LETTERS, 1) + randomFrom(SAFE_ALPHANUM, 5);
    }

    /**
     * 短随机 device_code 兜底（8 位无易混字符）。
     */
    public static String deviceCode() {
        return randomFrom(SAFE_ALPHANUM, 8);
    }

    private static String randomFrom(char[] alphabet, int length) {
        char[] buf = new char[length];
        for (int i = 0; i < length; i++) {
            buf[i] = alphabet[RANDOM.nextInt(alphabet.length)];
        }
        return new String(buf);
    }
}
