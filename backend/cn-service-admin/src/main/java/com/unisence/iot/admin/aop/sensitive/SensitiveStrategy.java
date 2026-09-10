package com.unisence.iot.admin.aop.sensitive;

import java.util.function.Function;

public enum SensitiveStrategy {
    USERNAME(DesensitizerUtil::username),
    PHONE(DesensitizerUtil::phone),
    ID_CARD(DesensitizerUtil::idCard),
    BANK_CARD(DesensitizerUtil::bankCard);

    private final Function<String, String> desensitizer;

    SensitiveStrategy(Function<String, String> desensitizer) {
        this.desensitizer = desensitizer;
    }

    public Function<String, String> desensitizer() {
        return desensitizer;
    }
}
