package com.unisence.iot.metadata;

/**
 * 「该设备确认不存在」的负缓存标记（metadata-sync-bus.md §6.8）。
 *
 * <p>记录<b>是在哪个水位上确认的</b>，而不是只记一个时间戳：设备随后被创建时水位会推进，
 * 标记因此自然失效。只靠 TTL 会在「刚创建的设备立刻上报」这一最常见场景里把它判成未知设备。
 *
 * <p>只有在确实完成了「追平水位 → 用新根重试 → 仍不存在」的完整流程后才允许写入。
 * <b>依赖失败（Redis/MySQL 不可用、等待超时）绝不能写 negative</b> —— 那会把一次外部故障
 * 固化成「这台设备不存在」的结论。
 */
public record NegativeDeviceMarker(long appliedHead, long checkedAtEpochMs) {

    /**
     * 当前根已经比确认时更新时，标记作废，必须重新判定。
     */
    public boolean isStale(long currentAppliedHead, long nowMs, long ttlMs) {
        return currentAppliedHead > appliedHead || nowMs - checkedAtEpochMs >= ttlMs;
    }
}
