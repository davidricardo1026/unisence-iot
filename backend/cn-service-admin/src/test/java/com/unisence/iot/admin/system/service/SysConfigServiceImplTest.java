package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.entity.SysConfig;
import com.unisence.iot.admin.mapper.SysConfigMapper;
import com.unisence.iot.admin.system.converter.SysConfigConverter;
import com.unisence.iot.admin.system.dto.ConfigCreateRequest;
import com.unisence.iot.admin.system.dto.ConfigUpdateRequest;
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
 * {@link SysConfigServiceImpl} 业务逻辑单元测试（写操作）。
 * 覆盖：参数键名唯一性、存在性校验、系统内置参数（configType=0）禁删护栏。
 * deleteConfig 落库经 removeById（TableInfo）不在单测范围，只测其护栏。
 */
class SysConfigServiceImplTest {

    private SysConfigMapper configMapper;
    private SysConfigConverter configConverter;
    private SysConfigServiceImpl service;

    @BeforeEach
    void setUp() {
        configMapper = mock(SysConfigMapper.class);
        configConverter = mock(SysConfigConverter.class);
        service = new SysConfigServiceImpl(configMapper, configConverter);
    }

    @Test
    @DisplayName("createConfig：键名已存在 → 409/2025，不插入")
    void createConfig_duplicateKey_conflict() {
        when(configMapper.selectCount(any())).thenReturn(1L);

        assertBusinessError(() -> service.createConfig(createRequest("sys.title")),
                            HttpStatus.CONFLICT, 2025);

        verify(configMapper, never()).insert(any(SysConfig.class));
    }

    @Test
    @DisplayName("createConfig：成功 → 插入并返回自增 id")
    void createConfig_success_returnsId() {
        when(configMapper.selectCount(any())).thenReturn(0L);
        SysConfig entity = new SysConfig();
        when(configConverter.toEntity(any())).thenReturn(entity);
        when(configMapper.insert(any(SysConfig.class))).thenAnswer(inv -> {
            ((SysConfig) inv.getArgument(0)).setConfigId(40L);
            return 1;
        });

        assertThat(service.createConfig(createRequest("sys.title"))).isEqualTo(40L);
    }

    @Test
    @DisplayName("updateConfig：参数不存在 → 404/2026")
    void updateConfig_notFound_notFound() {
        when(configMapper.selectById(7L)).thenReturn(null);

        assertBusinessError(() -> service.updateConfig(7L, updateRequest()), HttpStatus.NOT_FOUND, 2026);
    }

    @Test
    @DisplayName("updateConfig：成功 → 带乐观锁落库")
    void updateConfig_success_updates() {
        SysConfig existing = config(7L, 1);
        when(configMapper.selectById(7L)).thenReturn(existing);

        ConfigUpdateRequest req = updateRequest();
        req.setVersion(3);
        service.updateConfig(7L, req);

        assertThat(existing.getVersion()).isEqualTo(3);
        verify(configConverter).updateEntity(existing, req);
        verify(configMapper).updateByIdWithVersionCheck(existing);
    }

    @Test
    @DisplayName("deleteConfig：参数不存在 → 404/2026")
    void deleteConfig_notFound_notFound() {
        when(configMapper.selectById(9L)).thenReturn(null);

        assertBusinessError(() -> service.deleteConfig(9L), HttpStatus.NOT_FOUND, 2026);
    }

    @Test
    @DisplayName("deleteConfig：系统内置参数（configType=0）→ 403/2024")
    void deleteConfig_systemParam_forbidden() {
        when(configMapper.selectById(9L)).thenReturn(config(9L, 0));

        assertBusinessError(() -> service.deleteConfig(9L), HttpStatus.FORBIDDEN, 2024);
    }

    // ---------- helpers ----------

    private static SysConfig config(Long id, Integer type) {
        SysConfig c = new SysConfig();
        c.setConfigId(id);
        c.setConfigKey("k" + id);
        c.setConfigName("n");
        c.setConfigValue("v");
        c.setConfigType(type);
        return c;
    }

    private static ConfigCreateRequest createRequest(String key) {
        ConfigCreateRequest r = new ConfigCreateRequest();
        r.setConfigName("标题");
        r.setConfigKey(key);
        r.setConfigValue("平台");
        r.setConfigType(1);
        return r;
    }

    private static ConfigUpdateRequest updateRequest() {
        ConfigUpdateRequest r = new ConfigUpdateRequest();
        r.setConfigName("标题");
        r.setConfigValue("平台");
        r.setConfigType(1);
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
