package com.unisence.iot.common.validation;

public final class InvisibleUnicode {

    private InvisibleUnicode() {
    }

    public static boolean contains(CharSequence value) {
        return value != null && value.codePoints().anyMatch(InvisibleUnicode::isInvisible);
    }

    private static boolean isInvisible(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONTROL
            || type == Character.FORMAT
            || type == Character.SURROGATE
            || type == Character.LINE_SEPARATOR
            || type == Character.PARAGRAPH_SEPARATOR
            || codePoint == 0x034F
            || inRange(codePoint, 0x115F, 0x1160)
            || inRange(codePoint, 0x17B4, 0x17B5)
            || inRange(codePoint, 0x180B, 0x180F)
            || codePoint == 0x3164
            || inRange(codePoint, 0xFE00, 0xFE0F)
            || codePoint == 0xFFA0
            || inRange(codePoint, 0x1BCA0, 0x1BCAF)
            || inRange(codePoint, 0x1D173, 0x1D17A)
            || inRange(codePoint, 0xE0000, 0xE0FFF);
    }

    private static boolean inRange(int codePoint, int start, int end) {
        return codePoint >= start && codePoint <= end;
    }
}
