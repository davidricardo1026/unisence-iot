package com.unisence.iot.admin.metadata;

import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.metadata.MetadataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * §13.2 固定调用链的唯一实现点：
 *
 * <pre>
 * 业务 ApplicationService @Transactional
 *   → 业务校验/业务 DML
 *   → MetadataChangeRecorder.recordChanges(...)
 *   → MetadataChangeNotifier.publishAfterCommit(commitSeq)
 *   → COMMIT → afterCommit Redis max + publish
 * </pre>
 *
 * <p>把这两步收在一处，是为了让「记录后必然提示、且提示晚于记录」成为结构性事实，
 * 而不是每个业务方法各自记得写对顺序。业务侧只需回答一个问题：<b>这次改了哪些聚合根</b>。
 *
 * <p><b>这不是旁路</b>：本类没有任何绕过 recorder 直接写 change 表或直接 publish 旧水位的能力。
 *
 * <p>聚合根联动必须由掌握业务语义的 ApplicationService 显式传入 —— 禁止用实体 Listener、
 * Mapper AOP 或数据库 trigger 去「猜」变化范围：那些切面看得见行变化，却看不见
 * 「删产品要连带失效它的物模型与规则绑定」这类跨表语义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataCommit {

    private final MetadataChangeRecorder recorder;
    private final MetadataChangeNotifier notifier;

    /**
     * 单个聚合根变化的快捷入口。
     */
    public long commit(MetaKeyEnum metaKey, long scopeId) {
        return commit(Set.of(MetadataScope.of(metaKey, scopeId)));
    }

    /**
     * 记录本次事务的全部变化范围并登记提交后提示。
     *
     * <p>必须在业务 DML 完成之后、事务提交之前调用，且同一最外层事务只调用一次。
     *
     * @return 本次提交水位
     */
    public long commit(Set<MetadataScope> scopes) {
        long operatorId = currentOperatorId();
        long commitSeq = recorder.recordChanges(scopes, operatorId);
        notifier.publishAfterCommit(commitSeq);
        return commitSeq;
    }

    /**
     * 便于业务侧逐步累积变化范围；{@code LinkedHashSet} 保留顺序，排障时可复现。
     */
    public static Set<MetadataScope> scopes(MetadataScope... items) {
        Set<MetadataScope> set = new LinkedHashSet<>();
        java.util.Collections.addAll(set, items);
        return set;
    }

    /**
     * 当前操作者。
     *
     * <p>取不到时落到 {@code 0}（系统）而不是抛异常：变更目录的 {@code create_by} 只用于排障，
     * 让它把一次已经成功的业务保存变成失败是本末倒置。
     */
    private long currentOperatorId() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getLoginIdAsLong();
            }
        } catch (Exception e) {
            log.debug("获取当前用户ID失败，元数据变更记为系统操作", e);
        }
        return 0L;
    }
}
