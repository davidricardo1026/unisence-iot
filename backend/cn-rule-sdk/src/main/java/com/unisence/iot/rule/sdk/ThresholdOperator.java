package com.unisence.iot.rule.sdk;

/**
 * 阈值比较算子（{@code trigger_config.operator}）。
 *
 * <p>阈值判定必须显式带算子：只写 threshold 会让「温度低于 5 度」这类下界规则无法表达，
 * 逼用户在 filter 里反向取反，从而失去窗口聚合语义。
 */
public enum ThresholdOperator {

    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    EQ("=="),
    NE("!=");

    private final String symbol;

    ThresholdOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /**
     * 双精度比较。EQ/NE 使用 {@link Double#compare} 精确比较，不引入容差 —— 容差应由业务在 filter 阶段表达。
     */
    public boolean test(double actual, double threshold) {
        return switch (this) {
            case GT -> actual > threshold;
            case GTE -> actual >= threshold;
            case LT -> actual < threshold;
            case LTE -> actual <= threshold;
            case EQ -> Double.compare(actual, threshold) == 0;
            case NE -> Double.compare(actual, threshold) != 0;
        };
    }
}
