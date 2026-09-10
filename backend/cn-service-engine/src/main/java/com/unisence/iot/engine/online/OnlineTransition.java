package com.unisence.iot.engine.online;

/**
 * 一次待落库的状态跳变（device-online-state-design.md §7.1、§10.6）。
 *
 * <p>它是 {@link PendingOnlineTransition} 经 {@code DeviceMetadataCache} 解析出 {@code deviceId}
 * 之后的形态：{@code deviceKey} 用于批内合并与时序维度，{@code deviceId} 用于 MySQL 主键定位。
 *
 * <p>{@code (streamMs, streamSeq)} 来自 Redis stream id。Redis 保证同 stream 内 id 严格单调，
 * 因此这个二元组是天然的 fencing cursor —— 比设备侧时间戳可靠得多，后者受时钟漂移影响、跨实例不可比。
 *
 * @param transitionId stream id 原文，ACK 时用
 * @param reason       {@code device_online_log.reason}：connect / heartbeat_timeout / driver_unreachable
 */
public record OnlineTransition(
    int shard,
    String transitionId,
    long streamMs,
    long streamSeq,
    long deviceId,
    String deviceKey,
    String productKey,
    String deviceCode,
    DeviceOnlineState target,
    String reason,
    long changedAtMs) {

    public static final String REASON_MESSAGE = "connect";
    public static final String REASON_HEARTBEAT_TIMEOUT = "heartbeat_timeout";

    /**
     * {@code device_online_log.event}：1-上线 2-离线。UNKNOWN 不写该表。
     */
    public int onlineLogEvent() {
        return switch (target) {
            case ONLINE -> 1;
            case OFFLINE -> 2;
            default -> throw new IllegalStateException("状态 " + target + " 不写 device_online_log");
        };
    }

    public boolean writesOnlineLog() {
        return target == DeviceOnlineState.ONLINE || target == DeviceOnlineState.OFFLINE;
    }
}
