package com.unisence.iot.admin.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.unisence.iot.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

/**
 * 乐观锁版本化 Mapper，所有业务 Mapper 应扩展此接口而非直接扩展 {@link BaseMapper}。
 * <p>
 * 提供 4 个带乐观锁版本校验的包装方法。内部调用 MyBatis-Plus 原生方法（经 {@code OptimisticLockerInnerInterceptor} 改写 SQL），
 * 并检查受影响行数。若 &lt;= 0，统一抛出 HTTP 409。
 * </p>
 *
 * @param <T> 实体类型（必须扩展 {@code BaseEntity}，含 {@code @Version} 字段）
 */
public interface AppBaseMapper<T> extends BaseMapper<T> {

    /**
     * 乐观锁 updateById。调用 {@link BaseMapper#updateById(Object)} + affectedRows 校验。
     *
     * @return 受影响行数
     * @throws BusinessException HTTP 409 当 affectedRows &lt;= 0 时
     */
    default int updateByIdWithVersionCheck(T entity) {
        int rows = this.updateById(entity);
        if (rows <= 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2040,
                                        "数据已被他人修改，请刷新后重试");
        }
        return rows;
    }

    /**
     * 乐观锁 update(entity, wrapper)。调用 {@link BaseMapper#update(Object, Wrapper)} + affectedRows 校验。
     * <p>
     * <b>wrapper 不可复用</b>（拦截器会改写 wrapper 内容）。
     * </p>
     *
     * @return 受影响行数
     * @throws BusinessException HTTP 409 当 affectedRows &lt;= 0 时
     */
    default int updateWithVersionCheck(T entity, Wrapper<T> wrapper) {
        int rows = this.update(entity, wrapper);
        if (rows <= 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2040,
                                        "数据已被他人修改，请刷新后重试");
        }
        return rows;
    }

    /**
     * 乐观锁 insertOrUpdate。调用 {@link BaseMapper#insertOrUpdate(Object)}。
     * <p>
     * 底层 MySQL {@code INSERT ... ON DUPLICATE KEY UPDATE}，更新部分走乐观锁（拦截器改写 WHERE version=?）。
     * </p>
     *
     * @throws BusinessException HTTP 409 当操作失败时
     */
    default void insertOrUpdateWithVersionCheck(T entity) {
        boolean success = this.insertOrUpdate(entity);
        if (!success) {
            throw new BusinessException(HttpStatus.CONFLICT, 2040,
                                        "数据已被他人修改，请刷新后重试");
        }
    }
}
