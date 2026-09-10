package com.unisence.iot.admin.aop.sensitive;

public final class DesensitizerUtil {

    private DesensitizerUtil() {
    }

    public static String username(String source) {
        if (source == null || source.isEmpty()) return "";
        int len = source.length();
        if (len == 1) return source;
        if (len == 2) return source.charAt(0) + "*";
        return source.charAt(0) + "*".repeat(len - 2) + source.charAt(len - 1);
    }

    public static String phone(String source) {
        if (source == null || source.length() < 7) return source;
        return source.substring(0, 3) + "****" + source.substring(source.length() - 4);
    }

    public static String idCard(String source) {
        if (source == null || source.length() < 8) return source;
        return source.substring(0, 6) + "**********" + source.substring(source.length() - 2);
    }

    public static String bankCard(String source) {
        if (source == null || source.length() < 10) return source;
        int len = source.length();
        return source.substring(0, 6) + "*".repeat(len - 10) + source.substring(len - 4);
    }
}
