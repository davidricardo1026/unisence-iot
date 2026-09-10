package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.entity.SysDictData;
import com.unisence.iot.admin.mapper.SysDictDataMapper;
import com.unisence.iot.admin.mapper.SysDictTypeMapper;
import com.unisence.iot.admin.system.converter.SysDictDataConverter;
import com.unisence.iot.admin.system.dto.DictDataCreateRequest;
import com.unisence.iot.admin.system.dto.DictDataUpdateRequest;
import com.unisence.iot.admin.system.vo.DictDataVO;
import com.unisence.iot.common.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysDictDataServiceImpl} 业务逻辑单元测试。
 * 覆盖：所属类型须存在(2022)、标签唯一(2024)、键值唯一(2025)、数据存在性(2023)、按类型列举。
 */
class SysDictDataServiceImplTest {

    private SysDictDataMapper dictDataMapper;
    private SysDictTypeMapper dictTypeMapper;
    private SysDictDataConverter dictDataConverter;
    private SysDictDataServiceImpl service;

    @BeforeEach
    void setUp() {
        dictDataMapper = mock(SysDictDataMapper.class);
        dictTypeMapper = mock(SysDictTypeMapper.class);
        dictDataConverter = mock(SysDictDataConverter.class);
        service = new SysDictDataServiceImpl(dictDataMapper, dictTypeMapper, dictDataConverter);
    }

    @Test
    @DisplayName("createDictData：所属字典类型不存在 → 400/2022")
    void createDictData_typeMissing_badRequest() {
        when(dictTypeMapper.selectCount(any())).thenReturn(0L);

        assertBusinessError(() -> service.createDictData(createRequest()), HttpStatus.BAD_REQUEST, 2022);

        verify(dictDataMapper, never()).insert(any(SysDictData.class));
    }

    @Test
    @DisplayName("createDictData：同字典下标签重复 → 409/2024")
    void createDictData_duplicateLabel_conflict() {
        when(dictTypeMapper.selectCount(any())).thenReturn(1L); // 类型存在
        when(dictDataMapper.selectCount(any())).thenReturn(1L); // 标签已存在

        assertBusinessError(() -> service.createDictData(createRequest()), HttpStatus.CONFLICT, 2024);
    }

    @Test
    @DisplayName("createDictData：标签唯一但键值重复 → 409/2025")
    void createDictData_duplicateValue_conflict() {
        when(dictTypeMapper.selectCount(any())).thenReturn(1L);
        // 第一次(标签)为 0，第二次(键值)为 1
        when(dictDataMapper.selectCount(any())).thenReturn(0L, 1L);

        assertBusinessError(() -> service.createDictData(createRequest()), HttpStatus.CONFLICT, 2025);
    }

    @Test
    @DisplayName("createDictData：成功 → 插入并返回自增 id")
    void createDictData_success_returnsId() {
        when(dictTypeMapper.selectCount(any())).thenReturn(1L);
        when(dictDataMapper.selectCount(any())).thenReturn(0L); // 标签、键值均唯一
        SysDictData entity = new SysDictData();
        when(dictDataConverter.toEntity(any())).thenReturn(entity);
        when(dictDataMapper.insert(any(SysDictData.class))).thenAnswer(inv -> {
            ((SysDictData) inv.getArgument(0)).setDictDataId(60L);
            return 1;
        });

        assertThat(service.createDictData(createRequest())).isEqualTo(60L);
    }

    @Test
    @DisplayName("updateDictData：数据不存在 → 404/2023")
    void updateDictData_notFound_notFound() {
        when(dictDataMapper.selectById(7L)).thenReturn(null);

        assertBusinessError(() -> service.updateDictData(7L, updateRequest()), HttpStatus.NOT_FOUND, 2023);
    }

    @Test
    @DisplayName("listByDictType：按类型查出并映射为 VO")
    void listByDictType_mapsToVO() {
        SysDictData d = new SysDictData();
        d.setDictDataId(1L);
        d.setDictType("sys_user_status");
        when(dictDataMapper.selectList(any())).thenReturn(List.of(d));
        DictDataVO vo = new DictDataVO();
        vo.setDictDataId(1L);
        when(dictDataConverter.toVO(d)).thenReturn(vo);

        assertThat(service.listByDictType("sys_user_status"))
            .extracting(DictDataVO::getDictDataId).containsExactly(1L);
    }

    // ---------- helpers ----------

    private static DictDataCreateRequest createRequest() {
        DictDataCreateRequest r = new DictDataCreateRequest();
        r.setDictType("sys_user_status");
        r.setDictLabel("正常");
        r.setDictValue("1");
        r.setStatus(1);
        return r;
    }

    private static DictDataUpdateRequest updateRequest() {
        DictDataUpdateRequest r = new DictDataUpdateRequest();
        r.setDictLabel("正常");
        r.setDictValue("1");
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
