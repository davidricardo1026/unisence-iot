package com.unisence.iot.common.service;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.unisence.iot.common.entity.BaseEntity;

import java.io.Serializable;
import java.util.Collection;

/**
 * 公共 ServiceImpl 基类
 *
 * 覆盖 MP 默认的 removeById / removeByIds，
 * 将 deleted 字段赋值为当前记录的主键 ID，而非固定的 0/1，
 * 配合联合唯一索引 (business_field, deleted) 彻底解决逻辑删除冲突。
 *
 * 使用规则（铁律）：
 *   ✅ 业务删除必须调用 this.removeById(id) 或 this.removeByIds(ids)
 *   ❌ 严禁直接调用 xxxMapper.deleteById(id)，Mapper 层不感知主键注入逻辑
 *
 * @param <M> Mapper 类型
 * @param <T> 实体类型，必须继承 BaseEntity
 */
public abstract class BaseServiceImpl<M extends BaseMapper<T>, T>
    extends ServiceImpl<M, T> {

    /**
     * 单条删除处理
     *
     * 逻辑说明：
     * 1. 如果实体继承自 BaseEntity (含有 deleted 字段)，则执行逻辑删除：deleted = 主键ID。
     * 2. 如果实体未包含逻辑删除字段（如日志表、中间表），则执行 MP 默认的物理删除。
     */
    @Override
    public boolean removeById(Serializable id) {
        // 判断是否需要执行自定义的 "deleted = ID" 逻辑删除
        if (BaseEntity.class.isAssignableFrom(getEntityClass())) {
            // 动态获取当前实体类的主键列名（如 "user_id"、"role_id"、"config_id"）
            TableInfo tableInfo = TableInfoHelper.getTableInfo(getEntityClass());
            String pkColumn = tableInfo.getKeyColumn();

            return update(
                Wrappers.<T>update()
                    .setSql("deleted = " + id)
                    .eq(pkColumn, id)
                    .eq("deleted", 0L)
            );
        }

        // 否则走 MP 默认逻辑（物理删除或默认逻辑删除）
        return super.removeById(id);
    }

    /**
     * 批量逻辑删除
     * 每条记录的 deleted 值不同（等于自身主键），必须逐条处理，不能用 IN 一次更新
     */
    @Override
    public boolean removeByIds(Collection<?> idList) {
        if (idList == null || idList.isEmpty()) {
            return true;
        }
        for (Object id : idList) {
            removeById((Serializable) id);
        }
        return true;
    }
}
