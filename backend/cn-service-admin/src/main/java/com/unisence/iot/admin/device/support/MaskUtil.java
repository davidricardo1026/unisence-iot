package com.unisence.iot.admin.device.support;

import java.util.Map;

public final class MaskUtil {

    private MaskUtil() {
    }

    public static String mask(String plain, String mask, Map<String, Object> maskRule) {
        if (plain == null) {
            return null;
        }
        if (mask == null || mask.isBlank() || "all".equals(mask)) {
            return "******";
        }
        return switch (mask) {
            case "username" -> maskKeep(plain, 1, 0);
            case "phone" -> plain.length() >= 7 ? plain.substring(0,
                                                                  3) + "****" + plain.substring(plain.length() - 4) : "******";
            case "id_card" -> plain.length() >= 8 ? plain.substring(0,
                                                                    4) + "**********" + plain.substring(plain.length() - 4) : "******";
            case "bank_card" -> plain.length() >= 10 ? plain.substring(0,
                                                                       6) + "******" + plain.substring(plain.length() - 4) : "******";
            case "custom" -> custom(plain, maskRule);
            default -> "******";
        };
    }

    private static String custom(String plain, Map<String, Object> rule) {
        if (rule == null) {
            return "******";
        }
        int head = toInt(rule.get("keepHead"), 0);
        int tail = toInt(rule.get("keepTail"), 0);
        String ch = rule.get("maskChar") == null ? "*" : String.valueOf(rule.get("maskChar"));
        if (ch.isEmpty()) {
            ch = "*";
        } else {
            ch = ch.substring(0, 1);
        }
        return maskKeep(plain, head, tail, ch.charAt(0));
    }

    private static String maskKeep(String plain, int head, int tail) {
        return maskKeep(plain, head, tail, '*');
    }

    private static String maskKeep(String plain, int head, int tail, char maskChar) {
        int n = plain.codePointCount(0, plain.length());
        if (head + tail >= n) {
            return "******";
        }
        StringBuilder sb = new StringBuilder();
        plain.codePoints().limit(head).forEach(sb::appendCodePoint);
        int mid = n - head - tail;
        sb.append(String.valueOf(maskChar).repeat(Math.max(mid, 0)));
        int[] cps = plain.codePoints().toArray();
        for (int i = n - tail; i < n; i++) {
            sb.appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    private static int toInt(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(o.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
