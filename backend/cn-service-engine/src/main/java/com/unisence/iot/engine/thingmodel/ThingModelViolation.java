package com.unisence.iot.engine.thingmodel;

/**
 * 第 3 层物模型校验失败（device-message-contract.md §六 第 3 层）。
 *
 * <p>携带 {@link #reason()} 供 DLQ header 使用，携带 {@link #identifier()} 供日志定位到具体属性。
 * <b>整条</b>消息因此被拒，禁止半条落盘。
 */
public class ThingModelViolation extends RuntimeException {

    /**
     * 产品未定义物模型 —— 可能是产品未建、未发布，或消息投错了产品。
     */
    public static final String REASON_PRODUCT_UNKNOWN = "THING_MODEL_PRODUCT_UNKNOWN";
    /**
     * 上报了物模型里不存在的 identifier。
     */
    public static final String REASON_IDENTIFIER_UNKNOWN = "THING_MODEL_IDENTIFIER_UNKNOWN";
    /**
     * identifier 存在，但上报值的类型与 data_type 不符。
     */
    public static final String REASON_TYPE_MISMATCH = "THING_MODEL_TYPE_MISMATCH";

    private final String reason;
    private final String identifier;

    /**
     * <b>不抓调用栈</b>（{@code writableStackTrace=false}）。
     *
     * <p>本异常表达的是「这条消息按物模型该被整条拒绝」——
     * 一个<b>常规且预期</b>的结果，不是程序故障。两个捕获点
     * （{@code PropertyIngestionVerticle} / {@code EventIngestionVerticle}）
     * 都只读 {@link #reason()} / {@link #identifier()} / {@code getMessage()}，
     * <b>从不把异常对象交给日志</b>，因此栈自始至终无人使用。
     *
     * <p>而 {@code fillInStackTrace} 的代价与栈深成正比，且发生在**每条被拒消息**上 ——
     * 毒消息风暴或驱动改错标识符时正是量最大的时候，抓栈会把拒绝路径本身变成瓶颈
     * （2026-08-09 实测：事件参数配错导致的拒绝风暴中，
     * {@code ThingModelViolation.<init>} 在 wall 采样里仅次于 DLQ 投递本身）。
     *
     * <p>同时关闭 suppression：本异常不参与 try-with-resources，不需要该能力。
     */
    public ThingModelViolation(String reason, String identifier, String message) {
        super(message, null, false, false);
        this.reason = reason;
        this.identifier = identifier;
    }

    public String reason() {
        return reason;
    }

    /**
     * 产品级失败时为 {@code null}。
     */
    public String identifier() {
        return identifier;
    }
}
