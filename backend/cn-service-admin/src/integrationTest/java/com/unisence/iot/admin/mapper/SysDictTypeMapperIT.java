package com.unisence.iot.admin.mapper;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.unisence.iot.admin.entity.SysDictType;
import com.unisence.iot.admin.support.AbstractMysqlIntegrationTest;
import com.unisence.iot.admin.support.MapperItConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SysDictTypeMapper} 集成测试（Testcontainers 真 MySQL）。
 * <p>
 * 重点验证 {@link LambdaUpdateWrapper#set} 的按列更新 —— 这条路径**纯单元测试跑不了**：
 * {@code .set(Entity::getField, value)} 在构建期就要 eager 解析列名，依赖实体的 lambda 缓存（TableInfo），
 * mock mapper 场景没有 TableInfo 会抛 "can not find lambda cache"。真库注册了 TableInfo，故此处能验证。
 * （见 {@code architecture-features/test-strategy.md} §5 已知坑）。
 */
@SpringBootTest(classes = MapperItConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SysDictTypeMapperIT extends AbstractMysqlIntegrationTest {

    @Autowired
    private SysDictTypeMapper dictTypeMapper;

    @Test
    @DisplayName("LambdaUpdateWrapper.set：按 dict_type 条件改 dict_name，真库生效")
    void lambdaUpdateSet_renamesDictName() {
        SysDictType type = new SysDictType();
        type.setDictType("device_status");
        type.setDictName("设备状态-旧");
        type.setStatus(1);
        dictTypeMapper.insert(type);

        int rows = dictTypeMapper.update(null,
                                         new LambdaUpdateWrapper<SysDictType>()
                                             .set(SysDictType::getDictName, "设备状态-新")
                                             .eq(SysDictType::getDictType, "device_status"));

        assertThat(rows).isEqualTo(1);
        SysDictType reloaded = dictTypeMapper.selectById(type.getDictTypeId());
        assertThat(reloaded.getDictName()).isEqualTo("设备状态-新");
    }

    @Test
    @DisplayName("selectOne + LambdaQueryWrapper：按业务码 dict_type 唯一命中")
    void selectOne_byDictType() {
        SysDictType type = new SysDictType();
        type.setDictType("protocol_type");
        type.setDictName("协议类型");
        type.setStatus(1);
        dictTypeMapper.insert(type);

        SysDictType found = dictTypeMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysDictType>()
                .eq(SysDictType::getDictType, "protocol_type"));

        assertThat(found).isNotNull();
        assertThat(found.getDictName()).isEqualTo("协议类型");
    }
}
