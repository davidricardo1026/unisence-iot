package com.unisence.iot.rule.sdk;

/**
 * 规则脚本编译或执行失败。
 *
 * <p>不继承 BusinessException：cn-service-engine 无 HTTP 层，不应被迫依赖北向异常语义。
 * admin 侧在捕获后按 {@link #errorCode()} 转换为 BusinessException 返回 HTTP 4xx。
 */
public class RuleScriptException extends RuntimeException {

    private final RuleScriptErrorCode errorCode;

    public RuleScriptException(RuleScriptErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RuleScriptException(RuleScriptErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public RuleScriptErrorCode errorCode() {
        return errorCode;
    }

    public int code() {
        return errorCode.code();
    }
}
