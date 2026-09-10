package com.unisence.iot.admin.metadata;

import com.unisence.iot.admin.mapper.SysMetadataHeadMapper;
import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.metadata.MetadataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 运维手动操作入口（metadata-sync-bus.md §9.3）。
 *
 * <p>红线：手动操作<b>不是旁路改缓存</b>。admin 永远不直接调用 engine、不写实例状态、
 * 不清 engine 缓存 —— 那会绕过总线产生第二条同步路径，让「当前生效的是哪一代」不可推断。
 * 所有强制动作都退化成「在 MySQL 事务内产生一个新的 commit_seq」，再走完全相同的正常链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataRebuildService {

    private final MetadataChangeRecorder recorder;
    private final MetadataChangeNotifier notifier;
    private final SysMetadataHeadMapper headMapper;

    /**
     * 强制重建指定范围。
     *
     * <p>{@code scopeIds} 为空表示该域全量重建（{@code scope_id=0}）。
     *
     * @return 本次分配的新提交水位
     */
    @Transactional
    public long rebuild(MetaKeyEnum metaKey, Set<Long> scopeIds, long operatorId) {
        long commitSeq;
        if (scopeIds == null || scopeIds.isEmpty()) {
            commitSeq = recorder.recordFullRebuild(metaKey, operatorId);
        } else {
            Set<MetadataScope> scopes = new LinkedHashSet<>();
            for (Long scopeId : scopeIds) {
                scopes.add(MetadataScope.of(metaKey, scopeId == null ? 0L : scopeId));
            }
            commitSeq = recorder.recordChanges(scopes, operatorId);
        }
        notifier.publishAfterCommit(commitSeq);
        log.info("运维强制重建元数据: metaKey={} scopes={} commitSeq={} operatorId={}",
                 metaKey, scopeIds == null ? 0 : scopeIds.size(), commitSeq, operatorId);
        return commitSeq;
    }

    /**
     * 重新提醒：读当前权威水位并重发提示。
     *
     * <p>只能唤醒<b>落后</b>实例 —— 已同步到该水位的实例会幂等跳过。
     * 因此它不是「强制重建」的替代品：要让所有实例重跑构建，必须走 {@link #rebuild}
     * 产生新水位。
     *
     * @return 当前权威提交水位
     */
    public long renotify() {
        Long committed = headMapper.selectCommittedSeq();
        if (committed == null) {
            throw new IllegalStateException("us_sys_metadata_head 的 MAIN 单例行缺失");
        }
        notifier.publish(committed);
        log.info("已重发元数据变更提示: committedSeq={}", committed);
        return committed;
    }
}
