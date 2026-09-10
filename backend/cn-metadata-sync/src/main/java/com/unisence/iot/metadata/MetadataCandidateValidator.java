package com.unisence.iot.metadata;

/**
 * 候选元数据根激活前的校验钩子。
 *
 * <p>在候选根构建完成后、{@code metadataRef.set(candidate)} 之前被 {@code MetadataSyncService} 调用，
 * 启动引导与运行期收敛两条路径都经过它。抛出 {@link MetadataCandidateRejectedException} 时：
 * 引导必须失败；运行期收敛保留上一版完整快照并进入既有退避/告警路径。
 *
 * <p>实现必须是<b>纯内存判断</b>，不得发起 Kafka / HTTP / 数据库 I/O。
 */
@FunctionalInterface
public interface MetadataCandidateValidator {

    MetadataCandidateValidator NOOP = candidate -> {
    };

    /**
     * @param candidate 已构建完成、尚未安装的候选根
     * @throws MetadataCandidateRejectedException 候选不可接受，携带定位信息
     */
    void validate(EngineMetadataSnapshot candidate) throws MetadataCandidateRejectedException;
}
