package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.ConfigCreateRequest;
import com.unisence.iot.admin.system.dto.ConfigQuery;
import com.unisence.iot.admin.system.dto.ConfigUpdateRequest;
import com.unisence.iot.admin.system.service.SysConfigService;
import com.unisence.iot.admin.system.vo.ConfigVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageRequestArgumentResolver;
import com.unisence.iot.common.api.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * {@link SysConfigController} Web 层测试。standaloneSetup + mock service + PageRequest 解析器。
 */
class SysConfigControllerTest {

    private SysConfigService configService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        configService = mock(SysConfigService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysConfigController(configService))
            .setCustomArgumentResolvers(new PageRequestArgumentResolver())
            .build();
    }

    @Test
    @DisplayName("GET /system/configs：透传分页与 query.*，返回列表")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pageConfigs_bindsParams() throws Exception {
        ConfigVO vo = new ConfigVO();
        vo.setConfigId(1L);
        vo.setConfigKey("sys.title");
        when(configService.pageConfigs(any())).thenReturn(new PageResult<>(List.of(vo), 1L));

        mockMvc.perform(get("/system/configs")
                            .param("pageNum", "2").param("pageSize", "10")
                            .param("query.configKey", "sys"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.list[0].configKey").value("sys.title"));

        ArgumentCaptor<PageRequest<ConfigQuery>> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(configService).pageConfigs(captor.capture());
        assertThat(captor.getValue().getPageNum()).isEqualTo(2);
        assertThat(captor.getValue().getQuery().getConfigKey()).isEqualTo("sys");
    }

    @Test
    @DisplayName("POST /system/configs：合法请求透传并返回自增 id")
    void createConfig_valid_returnsId() throws Exception {
        when(configService.createConfig(any(ConfigCreateRequest.class))).thenReturn(40L);

        String body = "{\"configName\":\"标题\",\"configKey\":\"sys.title\",\"configValue\":\"平台\",\"configType\":1}";

        mockMvc.perform(post("/system/configs").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("40"));

        ArgumentCaptor<ConfigCreateRequest> captor = ArgumentCaptor.forClass(ConfigCreateRequest.class);
        verify(configService).createConfig(captor.capture());
        assertThat(captor.getValue().getConfigKey()).isEqualTo("sys.title");
    }

    @Test
    @DisplayName("POST /system/configs：configKey 为空 → 400，且不进 service")
    void createConfig_blankKey_badRequest() throws Exception {
        String body = "{\"configName\":\"标题\",\"configKey\":\"\",\"configValue\":\"平台\"}";

        mockMvc.perform(post("/system/configs").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());

        verify(configService, never()).createConfig(any());
    }

    @Test
    @DisplayName("PUT /system/configs/{id}：路径 id + 请求体透传")
    void updateConfig_valid_delegates() throws Exception {
        String body = "{\"configName\":\"标题\",\"configValue\":\"平台\",\"configType\":1,\"version\":0}";

        mockMvc.perform(put("/system/configs/7").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<ConfigUpdateRequest> captor = ArgumentCaptor.forClass(ConfigUpdateRequest.class);
        verify(configService).updateConfig(eq(7L), captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("PUT /system/configs/{id}：缺少 version → 400，且不进 service")
    void updateConfig_missingVersion_badRequest() throws Exception {
        String body = "{\"configName\":\"标题\",\"configValue\":\"平台\",\"configType\":1}";

        mockMvc.perform(put("/system/configs/7").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());

        verify(configService, never()).updateConfig(any(), any());
    }

    @Test
    @DisplayName("DELETE /system/configs/{id}：按路径 id 删除")
    void deleteConfig_delegates() throws Exception {
        mockMvc.perform(delete("/system/configs/9")).andExpect(status().isOk());
        verify(configService).deleteConfig(9L);
    }

    @Test
    @DisplayName("DELETE /system/configs/cache：命中 clearCache（字面量路径优先于 {configId}）")
    void clearCache_routesToClearCache() throws Exception {
        mockMvc.perform(delete("/system/configs/cache")).andExpect(status().isOk());
        verify(configService).clearCache();
        verify(configService, never()).deleteConfig(any());
    }
}
