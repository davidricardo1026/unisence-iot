package com.unisence.iot.admin.metadata.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 单个 engine 实例的同步状态（metadata-sync-bus.md §9.2）。
 *
 * <p>由 engine 每 10s 写入 {@code unisence:{metadata}:instance:{instanceId}}，admin 只读展示。
 *
 * <p>水位字段是<b>字符串</b>：engine 侧同样按十进制字符串编码，避免经过 JSON number（双精度）
 * 时把超过 {@code 2^53} 的 {@code BIGINT} 水位截断。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetadataInstanceVO(
    int schemaVersion,
    String instanceId,
    /* BOOTSTRAPPING / READY / CONVERGING / DEGRADED */
    String state,
    String appliedHead,
    String desiredHead,
    Long lastAttemptAt,
    Long lastSuccessAt,
    Long lastBuildDurationMs,
    Integer deviceCatalogEntries,
    Long deviceL1EstimatedWeightBytes,
    Double deviceL1HitRate,
    Double deviceL2HitRate,
    Double deviceDbFallbackRate,
    Integer consecutiveFailures,
    String lastErrorCode,
    /* 由 admin 按 committedHead - appliedHead 计算，不由 engine 上报 */
    Long lag) {

    /**
     * 附加落后水位差；engine 不知道当前权威 committedHead，只能由 admin 算。
     */
    public MetadataInstanceVO withLag(long committedHead) {
        long applied;
        try {
            applied = Long.parseLong(appliedHead);
        } catch (RuntimeException e) {
            return new MetadataInstanceVO(schemaVersion,
                                          instanceId,
                                          state,
                                          appliedHead,
                                          desiredHead,
                                          lastAttemptAt,
                                          lastSuccessAt,
                                          lastBuildDurationMs,
                                          deviceCatalogEntries,
                                          deviceL1EstimatedWeightBytes,
                                          deviceL1HitRate,
                                          deviceL2HitRate,
                                          deviceDbFallbackRate,
                                          consecutiveFailures,
                                          lastErrorCode,
                                          null);
        }
        return new MetadataInstanceVO(schemaVersion,
                                      instanceId,
                                      state,
                                      appliedHead,
                                      desiredHead,
                                      lastAttemptAt,
                                      lastSuccessAt,
                                      lastBuildDurationMs,
                                      deviceCatalogEntries,
                                      deviceL1EstimatedWeightBytes,
                                      deviceL1HitRate,
                                      deviceL2HitRate,
                                      deviceDbFallbackRate,
                                      consecutiveFailures,
                                      lastErrorCode,
                                      committedHead - applied);
    }
}
