package com.unisence.iot.engine.repository;

/**
 * 下游写入失败，<b>并携带「该不该重试」的判定</b>。
 *
 * <p>这个布尔值是入口能否安全隔离毒消息的前提。若把所有写失败一视同仁按次数降级，
 * 一次时序数据库宕机会让每一批都重试到阈值、再逐条失败，最终把<b>整条流排空进 DLQ</b> ——
 * 用一次基础设施抖动换掉全部数据，比卡住分区糟得多。
 *
 * <p>判定原则（保守）：只有<b>确定由数据本身导致</b>的失败才标记为不可重试；
 * 无法归类的异常一律按可重试处理 —— 卡住分区并报警，好过误删数据。
 */
public class EngineWriteException extends RuntimeException {

    private final boolean retryable;

    public EngineWriteException(boolean retryable, String message, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * {@code true}：基础设施问题（连接不上、超时），重放即可恢复，<b>禁止投 DLQ</b>。
     * {@code false}：语句被下游拒绝，重放多少次都一样，属于毒消息，应隔离到 DLQ。
     */
    public boolean retryable() {
        return retryable;
    }
}
