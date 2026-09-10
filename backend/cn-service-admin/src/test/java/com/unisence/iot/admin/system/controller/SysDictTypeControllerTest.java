package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.DictTypeCreateRequest;
import com.unisence.iot.admin.system.dto.DictTypeQuery;
import com.unisence.iot.admin.system.dto.DictTypeUpdateRequest;
import com.unisence.iot.admin.system.service.SysDictTypeService;
import com.unisence.iot.admin.system.vo.DictTypeVO;
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
 * {@link SysDictTypeController} Web 层测试。
 */
class SysDictTypeControllerTest {

    private SysDictTypeService dictTypeService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dictTypeService = mock(SysDictTypeService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysDictTypeController(dictTypeService))
            .setCustomArgumentResolvers(new PageRequestArgumentResolver())
            .build();
    }

    @Test
    @DisplayName("GET /system/dict/types：透传分页与 query.*，返回列表")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void pageDictTypes_bindsParams() throws Exception {
        DictTypeVO vo = new DictTypeVO();
        vo.setDictTypeId(1L);
        vo.setDictType("sys_user_status");
        when(dictTypeService.pageDictTypes(any())).thenReturn(new PageResult<>(List.of(vo), 1L));

        mockMvc.perform(get("/system/dict/types")
                            .param("pageNum", "1").param("pageSize", "10")
                            .param("query.dictType", "sys"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.list[0].dictType").value("sys_user_status"));

        ArgumentCaptor<PageRequest<DictTypeQuery>> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(dictTypeService).pageDictTypes(captor.capture());
        assertThat(captor.getValue().getQuery().getDictType()).isEqualTo("sys");
    }

    @Test
    @DisplayName("POST /system/dict/types：合法请求透传并返回自增 id")
    void createDictType_valid_returnsId() throws Exception {
        when(dictTypeService.createDictType(any(DictTypeCreateRequest.class))).thenReturn(50L);
        String body = "{\"dictName\":\"用户状态\",\"dictType\":\"sys_user_status\",\"status\":1}";

        mockMvc.perform(post("/system/dict/types").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("50"));

        ArgumentCaptor<DictTypeCreateRequest> captor = ArgumentCaptor.forClass(DictTypeCreateRequest.class);
        verify(dictTypeService).createDictType(captor.capture());
        assertThat(captor.getValue().getDictType()).isEqualTo("sys_user_status");
    }

    @Test
    @DisplayName("POST /system/dict/types：dictType 为空 → 400，且不进 service")
    void createDictType_blankType_badRequest() throws Exception {
        String body = "{\"dictName\":\"用户状态\",\"dictType\":\"\"}";

        mockMvc.perform(post("/system/dict/types").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());

        verify(dictTypeService, never()).createDictType(any());
    }

    @Test
    @DisplayName("PUT /system/dict/types/{id}：路径 id + 请求体透传")
    void updateDictType_valid_delegates() throws Exception {
        String body = "{\"dictName\":\"用户状态\",\"dictType\":\"sys_user_status\",\"status\":1,\"version\":0}";

        mockMvc.perform(put("/system/dict/types/7").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<DictTypeUpdateRequest> captor = ArgumentCaptor.forClass(DictTypeUpdateRequest.class);
        verify(dictTypeService).updateDictType(eq(7L), captor.capture());
        assertThat(captor.getValue().getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("PUT /system/dict/types/{id}：缺少 version → 400，且不进 service")
    void updateDictType_missingVersion_badRequest() throws Exception {
        String body = "{\"dictName\":\"用户状态\",\"dictType\":\"sys_user_status\",\"status\":1}";

        mockMvc.perform(put("/system/dict/types/7").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest());

        verify(dictTypeService, never()).updateDictType(any(), any());
    }

    @Test
    @DisplayName("DELETE /system/dict/types/{id}：按路径 id 删除")
    void deleteDictType_delegates() throws Exception {
        mockMvc.perform(delete("/system/dict/types/9")).andExpect(status().isOk());
        verify(dictTypeService).deleteDictType(9L);
    }
}
