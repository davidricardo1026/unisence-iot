package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.system.service.DictCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DictCatalogController} Web 层测试。
 * getCatalog 里的 {@code StpUtil.checkLogin()} 静态调用用 mockStatic 隔离。
 */
class DictCatalogControllerTest {

    private DictCatalogService dictCatalogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dictCatalogService = mock(DictCatalogService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DictCatalogController(dictCatalogService)).build();
    }

    @Test
    @DisplayName("GET /system/dict/catalog：先 checkLogin，再透传 sinceVersion")
    void getCatalog_checksLoginAndPassesVersion() throws Exception {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            when(dictCatalogService.getCatalog("v1")).thenReturn(null);

            mockMvc.perform(get("/system/dict/catalog").param("sinceVersion", "v1"))
                .andExpect(status().isOk());

            stp.verify(StpUtil::checkLogin);
            verify(dictCatalogService).getCatalog("v1");
        }
    }

    @Test
    @DisplayName("GET /system/dict/catalog：不传 sinceVersion → 入参为 null")
    void getCatalog_absentVersionIsNull() throws Exception {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            when(dictCatalogService.getCatalog(null)).thenReturn(null);

            mockMvc.perform(get("/system/dict/catalog"))
                .andExpect(status().isOk());

            stp.verify(StpUtil::checkLogin);
            verify(dictCatalogService).getCatalog(null);
        }
    }
}
