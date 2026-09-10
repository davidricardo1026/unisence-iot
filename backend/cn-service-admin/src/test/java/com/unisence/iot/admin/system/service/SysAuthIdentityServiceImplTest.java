package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.entity.SysAuthIdentity;
import com.unisence.iot.admin.mapper.SysAuthIdentityMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysAuthIdentityServiceImpl} 业务逻辑单元测试。
 * <p>
 * 唯一方法 {@code deleteByUserId}:{@code list} 按 userId 查出该用户全部凭证 → 提取 {@code identityId}
 * (过滤 null 脏数据)→ {@code removeByIds} 逐条逻辑删除。
 * <p>
 * {@code removeByIds} 内部经 {@link com.unisence.iot.common.service.BaseServiceImpl#removeById}
 * → {@code TableInfoHelper} 解析逻辑删除主键列,依赖 MyBatis 表元信息注册,纯单测无法覆盖
 * (同 {@code SysUserServiceImplTest#deleteUser} 的取舍)。故 spy 仅打桩最底层碰 {@code TableInfoHelper}
 * 的单条 {@code removeById} 加以隔离,让 {@code removeByIds} 的循环与空短路真实执行,
 * 从而验证「按 userId 查 → 提取 + 过滤 null → 逐条委托删除」整条业务链。
 * 真正的 {@code deleted = identityId} 落库行为已由 {@code LogicDeleteServiceIT} 证明,不在此重复。
 */
class SysAuthIdentityServiceImplTest {

    private SysAuthIdentityMapper authIdentityMapper;
    private SysAuthIdentityServiceImpl service;

    @BeforeEach
    void setUp() {
        authIdentityMapper = mock(SysAuthIdentityMapper.class);
        service = spy(new SysAuthIdentityServiceImpl());
        // ServiceImpl.list(wrapper) 委托 getBaseMapper().selectList(wrapper),注入 mock 让查询链真实跑通
        ReflectionTestUtils.setField(service, "baseMapper", authIdentityMapper);
        // 隔离碰 TableInfoHelper 的单条逻辑删除;removeByIds 的循环与空短路仍真实执行
        doReturn(true).when(service).removeById(any(Serializable.class));
    }

    @Test
    @DisplayName("deleteByUserId:按 userId 查出全部凭证,逐条委托 removeById 逻辑删除")
    void deleteByUserId_removesEveryIdentityOfUser() {
        when(authIdentityMapper.selectList(any()))
            .thenReturn(List.of(identity(1L), identity(2L), identity(3L)));

        service.deleteByUserId(42L);

        verify(authIdentityMapper).selectList(any()); // 先查后删
        verify(service).removeById(1L);
        verify(service).removeById(2L);
        verify(service).removeById(3L);
        verify(service, times(3)).removeById(any(Serializable.class));
    }

    @Test
    @DisplayName("deleteByUserId:identityId 为 null 的脏数据被过滤,不进入删除")
    void deleteByUserId_filtersNullIdentityId() {
        List<SysAuthIdentity> rows = new ArrayList<>();
        rows.add(identity(1L));
        rows.add(identity(null)); // 脏数据:主键缺失
        rows.add(identity(3L));
        when(authIdentityMapper.selectList(any())).thenReturn(rows);

        service.deleteByUserId(42L);

        verify(service).removeById(1L);
        verify(service).removeById(3L);
        verify(service, times(2)).removeById(any(Serializable.class)); // null 被 filter 掉
    }

    @Test
    @DisplayName("deleteByUserId:该用户无凭证 → removeByIds 空短路,不触发任何删除")
    void deleteByUserId_noIdentities_noDeletion() {
        when(authIdentityMapper.selectList(any())).thenReturn(List.of());

        service.deleteByUserId(42L);

        verify(authIdentityMapper).selectList(any());
        verify(service, never()).removeById(any(Serializable.class));
    }

    private static SysAuthIdentity identity(Long identityId) {
        SysAuthIdentity i = new SysAuthIdentity();
        i.setIdentityId(identityId);
        i.setUserId(42L);
        i.setIdentityType("local");
        i.setIdentifier("id-" + identityId);
        return i;
    }
}
