package com.unisence.iot.rule.sdk;

import java.util.List;

/**
 * 属性历史数据固定保留档位的全仓唯一取值域。
 */
public final class DataRetention {

    public static final int DEFAULT_DAYS = 180;
    public static final List<Integer> ALLOWED_DAYS = List.of(90, 180, 360);

    private DataRetention() {
    }

    public static int requireAllowed(int days) {
        if (!ALLOWED_DAYS.contains(days)) {
            throw new IllegalArgumentException("数据保留天数只允许 90、180、360: " + days);
        }
        return days;
    }

    public static String greptimeTtl(int days) {
        return requireAllowed(days) + "d";
    }
}
