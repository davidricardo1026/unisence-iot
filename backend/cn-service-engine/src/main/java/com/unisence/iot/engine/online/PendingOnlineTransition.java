package com.unisence.iot.engine.online;

/**
 * Redis Stream 中尚未确认的原始跳变（device-online-state-design.md §10.6）。
 *
 * <p>「原始」指它只携带 Lua 写入时能看到的信息 —— {@code deviceKey} 而非 {@code deviceId}。
 * 主键要由 drainer 用 {@code DeviceMetadataCache} 批量解析，禁止逐条查库。
 *
 * @param transitionId Redis 生成的 stream id，形如 {@code 1700000000000-0}
 * @param streamMs     stream id 的毫秒部分，落库进 {@code status_event_ms}
 * @param streamSeq    stream id 的序号部分，落库进 {@code status_event_seq}
 */
public record PendingOnlineTransition(
    int shard,
    String transitionId,
    long streamMs,
    long streamSeq,
    String deviceKey,
    DeviceOnlineState target,
    String reason,
    long changedAtMs) {

    /**
     * 解析 stream id。
     *
     * <p>Redis 保证同一个 stream 内 id 严格单调递增，因此 {@code (ms, seq)} 二元组是天然的
     * fencing cursor —— 这正是它比「设备侧时间戳」更适合当 fence 的原因：
     * 后者受设备时钟漂移影响，跨实例不可比。
     */
    public static PendingOnlineTransition of(int shard, String transitionId, String deviceKey,
                                             DeviceOnlineState target, String reason, long changedAtMs) {
        int dash = transitionId.indexOf('-');
        if (dash <= 0) {
            throw new IllegalArgumentException("非法 stream id: " + transitionId);
        }
        long ms = Long.parseLong(transitionId.substring(0, dash));
        long seq = Long.parseLong(transitionId.substring(dash + 1));
        return new PendingOnlineTransition(shard, transitionId, ms, seq, deviceKey, target, reason, changedAtMs);
    }

    public String productKey() {
        return deviceKey.substring(0, deviceKey.indexOf('.'));
    }

    public String deviceCode() {
        return deviceKey.substring(deviceKey.indexOf('.') + 1);
    }
}
