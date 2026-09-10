package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.CacheClearRequest;
import com.unisence.iot.admin.system.service.CacheAdminService;
import com.unisence.iot.admin.system.vo.CacheRegistryItemVO;
import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CacheAdminController} Web 层测试（standaloneSetup，断言原始返回体）。
 * 覆盖：注册表查询透传、清理透传 domain/scope、`@Valid`（domain 为空 → 400 且不进 service）。
 */
class CacheAdminControllerTest {

    private CacheAdminService cacheAdminService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        cacheAdminService = mock(CacheAdminService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CacheAdminController(cacheAdminService)).build();
    }

    @Test
    @DisplayName("GET /system/cache/registry：返回缓存域注册表")
    void registry_returnsList() throws Exception {
        CacheRegistryItemVO item = new CacheRegistryItemVO();
        item.setDomain("USER");
        item.setCacheName("user");
        when(cacheAdminService.listRegistry()).thenReturn(List.of(item));

        mockMvc.perform(get("/system/cache/registry"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].domain").value("USER"))
            .andExpect(jsonPath("$[0].cacheName").value("user"));
    }

    @Test
    @DisplayName("POST /system/cache/clear：合法请求透传 domain/scope")
    void clear_valid_delegates() throws Exception {
        mockMvc.perform(post("/system/cache/clear")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"domain\":\"ROLE\",\"scope\":\"ALL\"}"))
            .andExpect(status().isOk());

        ArgumentCaptor<CacheClearRequest> captor = ArgumentCaptor.forClass(CacheClearRequest.class);
        verify(cacheAdminService).clear(captor.capture());
        assertThat(captor.getValue().getDomain()).isEqualTo(CacheDomain.ROLE);
        assertThat(captor.getValue().getScope()).isEqualTo(ClearScope.ALL);
    }

    @Test
    @DisplayName("POST /system/cache/clear：domain 为空 → 400，且不进 service")
    void clear_nullDomain_badRequest() throws Exception {
        mockMvc.perform(post("/system/cache/clear")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"scope\":\"ALL\"}"))
            .andExpect(status().isBadRequest());

        verify(cacheAdminService, never()).clear(any());
    }
}
