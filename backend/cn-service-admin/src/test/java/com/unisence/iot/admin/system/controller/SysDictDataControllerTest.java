package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.DictDataCreateRequest;
import com.unisence.iot.admin.system.dto.DictDataUpdateRequest;
import com.unisence.iot.admin.system.service.SysDictDataService;
import com.unisence.iot.admin.system.vo.DictDataVO;
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
 * {@link SysDictDataController} Web 层测试。含按 dictType 路径查询。
 */
class SysDictDataControllerTest {

    private SysDictDataService dictDataService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dictDataService = mock(SysDictDataService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysDictDataController(dictDataService))
            .setCustomArgumentResolvers(new PageRequestArgumentResolver())
            .build();
    }

    @Test
    @DisplayName("GET /system/dict/data：分页查询返回列表")
    void pageDictData_returnsList() throws Exception {
        DictDataVO vo = new DictDataVO();
        vo.setDictDataId(1L);
        vo.setDictLabel("正常");
        when(dictDataService.pageDictData(any())).thenReturn(new PageResult<>(List.of(vo), 1L));

        mockMvc.perform(get("/system/dict/data").param("pageNum", "1").param("pageSize", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.list[0].dictLabel").value("正常"));
    }

    @Test
    @DisplayName("GET /system/dict/data/{dictType}：按类型返回该字典的数据项")
    void listByDictType_returnsItems() throws Exception {
        DictDataVO vo = new DictDataVO();
        vo.setDictDataId(1L);
        vo.setDictValue("1");
        when(dictDataService.listByDictType("sys_user_status")).thenReturn(List.of(vo));

        mockMvc.perform(get("/system/dict/data/sys_user_status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].dictValue").value("1"));

        verify(dictDataService).listByDictType("sys_user_status");
    }

    @Test
    @DisplayName("POST /system/dict/data：合法请求透传并返回自增 id")
    void createDictData_valid_returnsId() throws Exception {
        when(dictDataService.createDictData(any(DictDataCreateRequest.class))).thenReturn(60L);
        String body = "{\"dictType\":\"sys_user_status\",\"dictLabel\":\"正常\",\"dictValue\":\"1\",\"status\":1}";

        mockMvc.perform(post("/system/dict/data").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("60"));

        ArgumentCaptor<DictDataCreateRequest> captor = ArgumentCaptor.forClass(DictDataCreateRequest.class);
        verify(dictDataService).createDictData(captor.capture());
        assertThat(captor.getValue().getDictLabel()).isEqualTo("正常");
    }

    @Test
    @DisplayName("POST /system/dict/data：dictLabel 为空 → 400，且不进 service")
    void createDictData_blankLabel_badRequest() throws Exception {
        String body = "{\"dictType\":\"sys_user_status\",\"dictLabel\":\"\",\"dictValue\":\"1\"}";

        mockMvc.perform(post("/system/dict/data").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());

        verify(dictDataService, never()).createDictData(any());
    }

    @Test
    @DisplayName("PUT /system/dict/data/{id}：路径 id + 请求体透传")
    void updateDictData_valid_delegates() throws Exception {
        String body = "{\"dictLabel\":\"正常\",\"dictValue\":\"1\",\"status\":1,\"version\":0}";

        mockMvc.perform(put("/system/dict/data/7").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<DictDataUpdateRequest> captor = ArgumentCaptor.forClass(DictDataUpdateRequest.class);
        verify(dictDataService).updateDictData(eq(7L), captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("PUT /system/dict/data/{id}：缺少 version → 400，且不进 service")
    void updateDictData_missingVersion_badRequest() throws Exception {
        String body = "{\"dictLabel\":\"正常\",\"dictValue\":\"1\",\"status\":1}";

        mockMvc.perform(put("/system/dict/data/7").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());

        verify(dictDataService, never()).updateDictData(any(), any());
    }

    @Test
    @DisplayName("DELETE /system/dict/data/{id}：按路径 id 删除")
    void deleteDictData_delegates() throws Exception {
        mockMvc.perform(delete("/system/dict/data/9")).andExpect(status().isOk());
        verify(dictDataService).deleteDictData(9L);
    }
}
