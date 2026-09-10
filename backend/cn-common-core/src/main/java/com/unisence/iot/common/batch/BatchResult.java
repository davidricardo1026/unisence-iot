package com.unisence.iot.common.batch;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量写入的三分结果（batch-write-strategy.md §四）。
 *
 * <p>「部分成功」与「重试有没有用」是批量写入的两个必需语义。用 {@code Consumer<List<T>>}
 * 这类<b>全批成败</b>的签名无法表达它们，必然导致两个后果：
 * <ul>
 *   <li>一条脏数据拖垮整批 —— 499 条本可以写成功的也被判失败；</li>
 *   <li>确定性失败被无限重试 —— 主键冲突重试一万次仍然冲突，而重试期间新数据排在后面进不来，
 *       整条链路被一个毒药批次永久堵死。</li>
 * </ul>
 *
 * @param succeeded 已确认写入
 * @param retryable 瞬态失败（连接中断、超时、死锁），重试可能成功
 * @param poison    确定性失败（字段越界、类型不符、约束冲突），重试多少次都不会成功
 */
public record BatchResult<T>(List<T> succeeded, List<T> retryable, List<T> poison) {

    public BatchResult {
        succeeded = copyOrEmpty(succeeded);
        retryable = copyOrEmpty(retryable);
        poison = copyOrEmpty(poison);
    }

    public static <T> BatchResult<T> allSucceeded(List<T> items) {
        return new BatchResult<>(items, List.of(), List.of());
    }

    public static <T> BatchResult<T> allRetryable(List<T> items) {
        return new BatchResult<>(List.of(), items, List.of());
    }

    public static <T> BatchResult<T> allPoison(List<T> items) {
        return new BatchResult<>(List.of(), List.of(), items);
    }

    /**
     * 全部成功且无任何残留。
     *
     * <p>持久缓冲链路据此推进确认点（{@code XACK} / Kafka offset / 水位）——
     * <b>确认必须晚于写成功</b>，这是不可重建载荷唯一可靠的保证方式。
     */
    public boolean isCompletelyDone() {
        return retryable.isEmpty() && poison.isEmpty();
    }

    public boolean hasRetryable() {
        return !retryable.isEmpty();
    }

    public boolean hasPoison() {
        return !poison.isEmpty();
    }

    public int total() {
        return succeeded.size() + retryable.size() + poison.size();
    }

    /**
     * 合并多次分批调用的结果，供实现方内部按物理批上限切分后汇总。
     */
    public BatchResult<T> merge(BatchResult<T> other) {
        if (other == null || other.total() == 0) {
            return this;
        }
        List<T> ok = new ArrayList<>(succeeded);
        ok.addAll(other.succeeded);
        List<T> retry = new ArrayList<>(retryable);
        retry.addAll(other.retryable);
        List<T> bad = new ArrayList<>(poison);
        bad.addAll(other.poison);
        return new BatchResult<>(ok, retry, bad);
    }

    /**
     * 允许 null 元素：批中的项由业务定义，本类不对元素本身做假设。
     */
    private static <T> List<T> copyOrEmpty(List<T> items) {
        return items == null || items.isEmpty()
            ? List.of()
            : java.util.Collections.unmodifiableList(new ArrayList<>(items));
    }
}
