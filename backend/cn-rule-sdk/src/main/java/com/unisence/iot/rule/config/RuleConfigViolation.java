package com.unisence.iot.rule.config;

/**
 * 一条配置校验失败项。
 *
 * <p>校验器返回列表而不是抛首个异常：保存表单需要一次性把所有问题标到对应字段上，
 * 逐条抛异常会让用户改一个提交一次。{@code field} 使用点号路径（如 {@code window.retentionMillis}），
 * 前端据此定位到具体控件。
 */
public record RuleConfigViolation(String field, RuleConfigErrorCode code, String message) {

    public static RuleConfigViolation of(String field, RuleConfigErrorCode code, String message) {
        return new RuleConfigViolation(field, code, message);
    }

    @Override
    public String toString() {
        return "[" + code.code() + "] " + field + ": " + message;
    }
}
