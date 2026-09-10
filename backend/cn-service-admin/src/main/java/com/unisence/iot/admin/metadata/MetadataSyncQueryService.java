package com.unisence.iot.admin.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisence.iot.admin.mapper.SysMetadataHeadMapper;
import com.unisence.iot.admin.metadata.vo.MetadataInstanceVO;
import com.unisence.iot.admin.metadata.vo.MetadataSyncStatusVO;
import com.unisence.iot.common.metadata.MetadataRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 元数据总线的只读观测（metadata-sync-bus.md §九）。
 *
 * <p>落后判定<b>只用</b> {@code MySQL committedHead - 实例 appliedHead}。Redis head 与实例状态
 * 都不是正确性依赖：前者是镜像，后者是自报。Redis 整体不可用时状态接口只是看不到实例，
 * 不代表同步失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataSyncQueryService {

    private final SysMetadataHeadMapper headMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public MetadataSyncStatusVO status() {
        Long committed = headMapper.selectCommittedSeq();
        if (committed == null) {
            throw new IllegalStateException("us_sys_metadata_head 的 MAIN 单例行缺失");
        }
        String redisHead = null;
        List<MetadataInstanceVO> instances = List.of();
        try {
            redisHead = redisTemplate.opsForValue().get(MetadataRedisKeys.HEAD);
            instances = loadInstances(committed);
        } catch (Exception e) {
            // Redis 只承载观测；不可用时仍要把权威 committedHead 返回给运维
            log.error("读取元数据实例状态失败，仅返回 MySQL 权威水位: committedHead={}", committed, e);
        }
        return new MetadataSyncStatusVO(committed, redisHead, instances);
    }

    /**
     * 取存活实例状态。
     *
     * <p>顺序固定为「先按 score 惰性清理过期成员，再范围取成员，最后一次 MGET」：
     * <b>禁止用 {@code KEYS} 扫描</b> —— 那是 O(N) 全库遍历，会在实例数无关的情况下阻塞 Redis。
     *
     * <p>ZSET 的 score 存「状态 key 的过期时刻」，因此「谁还活着」退化成一次范围查询。
     * 状态 key 本身有 TTL，实例进程退出后自然消失；ZSET 成员则靠这里惰性清理。
     */
    private List<MetadataInstanceVO> loadInstances(long committedHead) {
        long now = System.currentTimeMillis();
        redisTemplate.opsForZSet().removeRangeByScore(
            MetadataRedisKeys.INSTANCE_INDEX, Double.NEGATIVE_INFINITY, now);
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(
            MetadataRedisKeys.INSTANCE_INDEX, now, Double.POSITIVE_INFINITY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> keys = ids.stream().map(MetadataRedisKeys::instanceStatus).toList();
        List<String> payloads = redisTemplate.opsForValue().multiGet(keys);
        if (payloads == null) {
            return List.of();
        }
        List<MetadataInstanceVO> instances = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            if (payload == null) {
                // ZSET 成员还在但状态 key 已过期：实例刚退出，下一轮清理会摘掉成员
                continue;
            }
            try {
                instances.add(objectMapper.readValue(payload, MetadataInstanceVO.class)
                                  .withLag(committedHead));
            } catch (Exception e) {
                log.error("解析 engine 实例状态失败，已跳过该实例: payload={}", payload, e);
            }
        }
        return instances;
    }
}
