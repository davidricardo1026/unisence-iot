package com.unisence.iot.admin.aop.annotation;

public enum BusinessType {
    OTHER(0), INSERT(1), UPDATE(2), DELETE(3), GRANT(4), FORCE_KICKOUT(5), CLEAN(6), EXPORT(7);

    private final int code;

    BusinessType(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
