package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.entity.SysDictData;
import com.unisence.iot.admin.entity.SysDictType;
import com.unisence.iot.admin.mapper.SysDictDataMapper;
import com.unisence.iot.admin.mapper.SysDictTypeMapper;
import com.unisence.iot.common.api.dict.DictCatalogVO;
import com.unisence.iot.common.api.dict.DictItemVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link DictCatalogServiceImpl} 单元测试。
 * 覆盖：启用字典按 dictType 分组 + 按 sort 升序、version 取最大 updateTime 格式化、
 * sinceVersion 命中当前版本返回 null（未变更 304 语义）、无启用类型时短路不查数据表。
 */
class DictCatalogServiceImplTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SysDictTypeMapper dictTypeMapper = mock(SysDictTypeMapper.class);
    private final SysDictDataMapper dictDataMapper = mock(SysDictDataMapper.class);
    private final DictCatalogServiceImpl service = new DictCatalogServiceImpl(dictTypeMapper, dictDataMapper);

    @Test
    @DisplayName("getCatalog：按 dictType 分组、组内按 sort 升序，version=最大 updateTime")
    void getCatalog_groupsAndSortsByType() {
        LocalDateTime typeTime = LocalDateTime.of(2026, 7, 10, 10, 0, 0);
        LocalDateTime dataTime = LocalDateTime.of(2026, 7, 10, 12, 30, 0); // 更晚 → 决定 version
        when(dictTypeMapper.selectList(any())).thenReturn(List.of(type("device_status", typeTime)));
        when(dictDataMapper.selectList(any())).thenReturn(List.of(
            data("device_status", "1", "采集中", 2, dataTime),
            data("device_status", "2", "已掉线", 1, typeTime)));

        DictCatalogVO catalog = service.getCatalog(null);

        assertThat(catalog).isNotNull();
        assertThat(catalog.types()).containsOnlyKeys("device_status");
        // 组内按 sort 升序：sort=1 的 "2" 在 sort=2 的 "1" 之前
        assertThat(catalog.types().get("device_status"))
            .extracting(DictItemVO::value).containsExactly("2", "1");
        assertThat(catalog.version()).isEqualTo(dataTime.format(FMT));
    }

    @Test
    @DisplayName("getCatalog：sinceVersion 命中当前版本 → 返回 null（未变更）")
    void getCatalog_sameVersion_returnsNull() {
        LocalDateTime t = LocalDateTime.of(2026, 7, 10, 9, 0, 0);
        when(dictTypeMapper.selectList(any())).thenReturn(List.of(type("protocol_type", t)));
        when(dictDataMapper.selectList(any())).thenReturn(List.of()); // 无数据 → version 取类型时间

        assertThat(service.getCatalog(t.format(FMT))).isNull();
        // 不同版本则照常返回
        assertThat(service.getCatalog("19700101000000")).isNotNull();
    }

    @Test
    @DisplayName("getCatalog：无启用类型 → 空目录，且短路不查字典数据表")
    void getCatalog_noEnabledTypes_shortCircuits() {
        when(dictTypeMapper.selectList(any())).thenReturn(List.of());

        DictCatalogVO catalog = service.getCatalog(null);

        assertThat(catalog.types()).isEmpty();
        verify(dictDataMapper, never()).selectList(any());
    }

    private static SysDictType type(String dictType, LocalDateTime updateTime) {
        SysDictType t = new SysDictType();
        t.setDictType(dictType);
        t.setStatus(1);
        t.setUpdateTime(updateTime);
        return t;
    }

    private static SysDictData data(String dictType, String value, String label, int sort, LocalDateTime updateTime) {
        SysDictData d = new SysDictData();
        d.setDictType(dictType);
        d.setDictValue(value);
        d.setDictLabel(label);
        d.setSortOrder(sort);
        d.setStatus(1);
        d.setUpdateTime(updateTime);
        return d;
    }
}
