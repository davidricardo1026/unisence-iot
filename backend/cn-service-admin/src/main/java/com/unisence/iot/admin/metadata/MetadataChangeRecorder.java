package com.unisence.iot.admin.metadata;

import com.unisence.iot.admin.entity.SysMetadataChange;
import com.unisence.iot.admin.mapper.SysMetadataChangeMapper;
import com.unisence.iot.admin.mapper.SysMetadataHeadMapper;
import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.metadata.MetadataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在业务事务内分配提交水位并写变更目录（metadata-sync-bus.md §3.4、§13.2）。
 *
 * <p>这是 Transactional Outbox 的轻量变体：业务 DML 与「什么范围变了」在<b>同一个 MySQL 事务</b>提交，
 * 从根上消除「业务成功但可靠变更记录丢失」的双写窗口。engine 因此可以把
 * {@code us_sys_metadata_change} 当作可补读的权威变化目录，而不需要 Kafka/Redis Stream 再持久一份。
 *
 * <p><b>调用位置严格固定</b>：业务校验、脚本编译、引用校验和业务 DML 全部完成之后，事务的最后一步。
 * 原因是单例 head 行的写锁一直持有到事务结束，锁内多做一件事就多阻塞一次全局元数据提交。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataChangeRecorder {

    /**
     * 「本事务已记过一次」的标记。
     *
     * <p>绑在 {@link TransactionSynchronizationManager} 而不是裸 {@code ThreadLocal}：
     * 后者在事务结束后不会自动清理，线程池复用会让下一个请求误判成「已记录过」。
     */
    private static final Object RECORDED_MARKER = new Object();

    private final SysMetadataHeadMapper headMapper;
    private final SysMetadataChangeMapper changeMapper;
    private final MetadataSyncProperties properties;

    /**
     * 分配提交水位并写入本次变化范围。
     *
     * <p>必须在已激活的 Spring 业务事务末尾调用，且<b>同一最外层事务只允许调用一次</b>：
     * 嵌套领域服务只负责返回/汇总 {@link MetadataScope}，由最外层 ApplicationService 汇总后一次记录。
     * 一个事务分配两个水位会让 engine 在两个水位之间观察到「业务已改完但目录只有一半」的状态。
     *
     * @param scopes     去重前的变化范围，不得为空
     * @param operatorId 触发本次变更的用户 ID
     * @return 本次提交水位
     */
    public long recordChanges(Set<MetadataScope> scopes, long operatorId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 事务外调用意味着业务 DML 与变更目录不在同一提交边界内，Outbox 的全部保证都会失效
            throw new IllegalStateException("recordChanges 必须在已激活的业务事务内调用");
        }
        if (scopes == null || scopes.isEmpty()) {
            // 空集合说明调用方没想清楚「这次改了什么」；静默放行会让 engine 永远收不到该变更
            throw new IllegalArgumentException("变化范围不能为空");
        }
        markRecordedOnce();

        Set<MetadataScope> effective = collapse(scopes);
        long commitSeq = allocateCommitSeq(operatorId);
        insertChanges(effective, commitSeq);

        log.debug("已分配元数据提交水位: commitSeq={} scopes={} operatorId={}",
                  commitSeq, effective.size(), operatorId);
        return commitSeq;
    }

    /**
     * 记录一次全域强制重建（{@code scope_id=0}）。
     *
     * <p>必须分配<b>新的</b> commit_seq，而不是重发旧水位：已同步到当前水位的实例会把重复提示幂等跳过，
     * 那样「强制重建」就成了空操作。新水位才能让所有实例都真正走一遍构建。
     */
    public long recordFullRebuild(MetaKeyEnum metaKey, long operatorId) {
        if (metaKey == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2041, "重建范围 metaKey 不能为空");
        }
        return recordChanges(Set.of(MetadataScope.fullRebuild(metaKey)), operatorId);
    }

    /**
     * 打上「本事务已记过一次」的标记，并保证事务结束后一定摘除。
     *
     * <p>{@code TransactionSynchronizationManager.clear()} <b>不会</b>清理 {@code bindResource}
     * 绑定的资源 —— 那是绑定方自己的责任。少了 {@code afterCompletion} 的摘除，标记会随线程池复用
     * 泄漏到下一个请求，表现为「第二次业务保存莫名报重复分配」。
     */
    private void markRecordedOnce() {
        if (TransactionSynchronizationManager.hasResource(RECORDED_MARKER)) {
            throw new IllegalStateException("同一业务事务只允许分配一次 commit_seq，请由最外层服务汇总 MetadataScope");
        }
        TransactionSynchronizationManager.bindResource(RECORDED_MARKER, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(RECORDED_MARKER);
            }
        });
    }

    /**
     * 去重，并在单域 scope 数超阈值时坍缩成该域的全域重建。
     *
     * <p>批量导入十万台设备时逐条记 scope 会让变更目录本身成为瓶颈，而且 engine 增量路径的收益此时
     * 已经消失 —— 流式重建紧凑版本目录反而更快。
     */
    private Set<MetadataScope> collapse(Set<MetadataScope> scopes) {
        // 先按域分组计数，避免为了判断阈值而遍历两遍
        java.util.EnumMap<MetaKeyEnum, Integer> counts = new java.util.EnumMap<>(MetaKeyEnum.class);
        for (MetadataScope scope : scopes) {
            counts.merge(scope.metaKey(), 1, Integer::sum);
        }
        Set<MetaKeyEnum> collapsed = java.util.EnumSet.noneOf(MetaKeyEnum.class);
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > properties.getFullRebuildScopeThreshold()) {
                collapsed.add(entry.getKey());
                log.info("单次提交 scope 数超阈值，坍缩为全域重建: metaKey={} scopes={} threshold={}",
                         entry.getKey(), entry.getValue(), properties.getFullRebuildScopeThreshold());
            }
        }

        // LinkedHashSet 保留插入序，使同一批变更的落库顺序可复现，排障时更容易比对
        Set<MetadataScope> effective = new LinkedHashSet<>();
        for (MetadataScope scope : scopes) {
            if (collapsed.contains(scope.metaKey())) {
                continue;
            }
            effective.add(scope);
        }
        for (MetaKeyEnum metaKey : collapsed) {
            effective.add(MetadataScope.fullRebuild(metaKey));
        }
        return effective;
    }

    /**
     * 分配水位。
     *
     * <p>两条语句依赖 {@code LAST_INSERT_ID} 的<b>连接级</b>语义，因此中间绝不能穿插会另取连接的调用；
     * 由 Spring 事务同步保证二者落在同一连接上。
     */
    private long allocateCommitSeq(long operatorId) {
        int rows = headMapper.advanceHead(operatorId);
        if (rows != 1) {
            // MAIN 行缺失说明初始化脚本未执行；此时放行会让整条总线永远不推进
            throw new IllegalStateException("us_sys_metadata_head 的 MAIN 单例行缺失，无法分配提交水位");
        }
        return headMapper.lastAllocatedSeq();
    }

    private void insertChanges(Set<MetadataScope> scopes, long commitSeq) {
        List<SysMetadataChange> rows = new ArrayList<>(scopes.size());
        for (MetadataScope scope : scopes) {
            SysMetadataChange row = new SysMetadataChange();
            row.setCommitSeq(commitSeq);
            row.setMetaKey(scope.metaKey().getKey());
            row.setScopeId(scope.scopeId());
            rows.add(row);
        }
        // 取得水位之后只做这一件事然后提交：锁内禁止 Redis / Nacos / Kafka / HTTP / 耗时编译
        for (SysMetadataChange row : rows) {
            changeMapper.insert(row);
        }
    }
}
