package com.unisence.iot.rule.config;

/**
 * 规则配置校验错误码，占用 api-standard.md 登记的 5000-5999 区间中的 5041-5069 段
 * （5001-5031 属脚本编译与执行，见 {@code RuleScriptErrorCode}）。
 *
 * <p>2026-08-03 随配置模型重设计删除三个：{@code COMBINATION_INVALID}(5041)、
 * {@code TRIGGER_CONFIG_INVALID}(5045)、{@code PRIORITY_OUT_OF_RANGE}(5047)。
 * 它们对应的非法配置现在<b>结构上不可表达</b> —— 窗口与聚合列只存在于窗口规则表，
 * 触发策略与优先级整个删除。<b>号段留空不复用</b>：复用会让旧客户端把新错误当成旧错误。
 */
public enum RuleConfigErrorCode {

    LISTENER_CONFIG_INVALID(5042, "监听配置非法"),
    WINDOW_CONFIG_INVALID(5043, "窗口配置非法"),
    AGGREGATE_CONFIG_INVALID(5044, "聚合配置非法"),
    RETENTION_TOO_SHORT(5046, "窗口状态保留时长不足以覆盖窗口与宽限期"),
    IDENTIFIER_NOT_IN_THING_MODEL(5048, "引用的 identifier 不存在于产品物模型"),
    IDENTIFIER_TYPE_MISMATCH(5049, "引用的 identifier 数据类型不支持该聚合"),
    /**
     * 枚举值存在但第一版不开放；与「取值非法」区分，便于前端提示「暂不支持」而非「填错了」。
     */
    FEATURE_NOT_AVAILABLE(5050, "该配置项在当前版本不开放"),
    WINDOW_CAPACITY_EXCEEDED(5051, "窗口参数超出容量边界"),
    /**
     * 档位定义非法：档位为空、severity 重复、条件必填项缺失、取值配置缺失。
     */
    LEVEL_CONFIG_INVALID(5052, "档位配置非法"),
    /**
     * 即时规则缺少被监控信号的取值配置，而它有阈值型档位。
     */
    VALUE_CONFIG_INVALID(5053, "取值配置非法");

    private final int code;
    private final String message;

    RuleConfigErrorCode(int code, String message) {
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
