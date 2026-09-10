package com.unisence.iot.rule.sdk;

/**
 * 规则引擎脚本错误码，占用 api-standard.md 登记的 5000-5999 区间。
 *
 * <p>该区间同时被 cn-service-admin（保存校验，以 BusinessException 返回 HTTP 4xx）与
 * cn-service-engine（运行时无 HTTP，按 {@link ErrorPolicy} 走 DLQ 并打指标）复用，
 * 因此常量定义在 cn-common 而非任一服务私有包。
 */
public enum RuleScriptErrorCode {

    SCRIPT_TOO_LONG(5001, "脚本字符数超出限制"),
    SCRIPT_COMPILE_FAILED(5002, "脚本编译或静态类型检查失败"),
    SCRIPT_FORBIDDEN_SYNTAX(5003, "脚本命中沙箱禁止语法"),
    SCRIPT_TOO_COMPLEX(5004, "脚本 AST 复杂度超出限制"),

    FILTER_RETURN_TYPE(5011, "filter 脚本返回值必须是 Boolean"),
    OUTPUT_RETURN_TYPE(5012, "output 脚本返回值必须是 Map"),
    OUTPUT_KEY_TYPE(5013, "输出 Map 的键必须是 String"),
    OUTPUT_TOO_LARGE(5014, "输出字段数、嵌套深度或字节数超出限制"),
    OUTPUT_RESERVED_KEY(5015, "输出不得覆盖平台可信信封字段"),
    OUTPUT_VALUE_TYPE(5016, "输出值类型不可序列化"),

    SCRIPT_TIMEOUT(5021, "脚本执行超时"),
    SCRIPT_RUNTIME_ERROR(5022, "脚本执行期异常"),

    CONTEXT_VALUE_ERROR(5031, "运行上下文取值失败");

    private final int code;
    private final String message;

    RuleScriptErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
