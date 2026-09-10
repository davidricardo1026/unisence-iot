package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.entity.SysDictType;
import com.unisence.iot.admin.mapper.SysDictDataMapper;
import com.unisence.iot.admin.mapper.SysDictTypeMapper;
import com.unisence.iot.admin.system.converter.SysDictTypeConverter;
import com.unisence.iot.admin.system.dto.DictTypeCreateRequest;
import com.unisence.iot.admin.system.dto.DictTypeUpdateRequest;
import com.unisence.iot.common.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysDictTypeServiceImpl} 业务逻辑单元测试。
 * 覆盖：字典类型唯一(2020)、存在性(2021)、改名级联更新字典数据、有数据禁删(2019)。
 */
class SysDictTypeServiceImplTest {

    private SysDictTypeMapper dictTypeMapper;
    private SysDictDataMapper dictDataMapper;
    private SysDictTypeConverter dictTypeConverter;
    private SysDictTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        dictTypeMapper = mock(SysDictTypeMapper.class);
        dictDataMapper = mock(SysDictDataMapper.class);
        dictTypeConverter = mock(SysDictTypeConverter.class);
        service = new SysDictTypeServiceImpl(dictTypeMapper, dictDataMapper, dictTypeConverter);
    }

    @Test
    @DisplayName("createDictType：类型已存在 → 409/2020，不插入")
    void createDictType_duplicate_conflict() {
        when(dictTypeMapper.selectCount(any())).thenReturn(1L);

        assertBusinessError(() -> service.createDictType(createRequest("sys_user_status")),
                            HttpStatus.CONFLICT, 2020);

        verify(dictTypeMapper, never()).insert(any(SysDictType.class));
    }

    @Test
    @DisplayName("createDictType：成功 → 插入并返回自增 id")
    void createDictType_success_returnsId() {
        when(dictTypeMapper.selectCount(any())).thenReturn(0L);
        SysDictType entity = new SysDictType();
        when(dictTypeConverter.toEntity(any())).thenReturn(entity);
        when(dictTypeMapper.insert(any(SysDictType.class))).thenAnswer(inv -> {
            ((SysDictType) inv.getArgument(0)).setDictTypeId(50L);
            return 1;
        });

        assertThat(service.createDictType(createRequest("sys_user_status"))).isEqualTo(50L);
    }

    @Test
    @DisplayName("updateDictType：类型不存在 → 404/2021")
    void updateDictType_notFound_notFound() {
        when(dictTypeMapper.selectById(5L)).thenReturn(null);

        assertBusinessError(() -> service.updateDictType(5L, updateRequest("sys_user_status")),
                            HttpStatus.NOT_FOUND, 2021);
    }

    @Test
    @DisplayName("updateDictType：类型未变 → 带乐观锁落库，不触发级联")
    void updateDictType_typeUnchanged_updates() {
        SysDictType type = dictType(5L, "sys_user_status");
        when(dictTypeMapper.selectById(5L)).thenReturn(type);
        when(dictTypeMapper.selectCount(any())).thenReturn(0L); // 唯一性通过

        DictTypeUpdateRequest req = updateRequest("sys_user_status"); // 与现有 dictType 相同
        req.setVersion(2);
        service.updateDictType(5L, req);

        assertThat(type.getVersion()).isEqualTo(2);
        verify(dictTypeMapper).updateByIdWithVersionCheck(type);
        // 未改名 → 不进级联分支（LambdaUpdateWrapper.set 需 MyBatis 列缓存，归 DB 集成测试）
        verify(dictDataMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("deleteDictType：类型下存在数据 → 409/2019")
    void deleteDictType_hasData_conflict() {
        when(dictTypeMapper.selectById(9L)).thenReturn(dictType(9L, "sys_user_status"));
        when(dictDataMapper.selectCount(any())).thenReturn(2L);

        assertBusinessError(() -> service.deleteDictType(9L), HttpStatus.CONFLICT, 2019);
    }

    // ---------- helpers ----------

    private static SysDictType dictType(Long id, String type) {
        SysDictType t = new SysDictType();
        t.setDictTypeId(id);
        t.setDictName("n");
        t.setDictType(type);
        t.setStatus(1);
        return t;
    }

    private static DictTypeCreateRequest createRequest(String type) {
        DictTypeCreateRequest r = new DictTypeCreateRequest();
        r.setDictName("用户状态");
        r.setDictType(type);
        r.setStatus(1);
        return r;
    }

    private static DictTypeUpdateRequest updateRequest(String type) {
        DictTypeUpdateRequest r = new DictTypeUpdateRequest();
        r.setDictName("用户状态");
        r.setDictType(type);
        r.setStatus(1);
        r.setVersion(0);
        return r;
    }

    private static void assertBusinessError(ThrowingCallable call, HttpStatus status, int code) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(status);
                assertThat(ex.getCode()).isEqualTo(code);
            });
    }
}
