package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.OperLogQuery;
import com.unisence.iot.admin.system.service.SysOperLogService;
import com.unisence.iot.admin.system.vo.OperLogVO;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SysOperLogController} Web 层测试（standaloneSetup）。
 * 覆盖：分页绑定+透传、detail 路径透传、批量删除请求体透传、整表清空。
 */
class SysOperLogControllerTest {

    private SysOperLogService operLogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        operLogService = mock(SysOperLogService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysOperLogController(operLogService))
            .setCustomArgumentResolvers(new PageRequestArgumentResolver())
            .build();
    }

    @Test
    @DisplayName("GET /system/oper-logs：分页参数绑定并透传")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void page_bindsAndDelegates() throws Exception {
        OperLogVO vo = new OperLogVO();
        vo.setOperLogId(5L);
        when(operLogService.pageOperLogs(any())).thenReturn(new PageResult<>(List.of(vo), 1L));

        mockMvc.perform(get("/system/oper-logs")
                            .param("pageNum", "1").param("pageSize", "10")
                            .param("query.title", "用户"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.list[0].operLogId").value(5));

        ArgumentCaptor<PageRequest<OperLogQuery>> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(operLogService).pageOperLogs(captor.capture());
        assertThat(captor.getValue().getQuery().getTitle()).isEqualTo("用户");
    }

    @Test
    @DisplayName("GET /system/oper-logs/{id}：按路径 id 查询详情")
    void detail_delegatesPathId() throws Exception {
        when(operLogService.detail(7L)).thenReturn(new OperLogVO());

        mockMvc.perform(get("/system/oper-logs/7")).andExpect(status().isOk());

        verify(operLogService).detail(7L);
    }

    @Test
    @DisplayName("DELETE /system/oper-logs：请求体 id 列表透传批量删除")
    void batchDelete_bodyIds() throws Exception {
        mockMvc.perform(delete("/system/oper-logs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[1,2,3]"))
            .andExpect(status().isOk());

        verify(operLogService).batchDelete(List.of(1L, 2L, 3L));
    }

    @Test
    @DisplayName("DELETE /system/oper-logs/clean：整表清空")
    void cleanAll_delegates() throws Exception {
        mockMvc.perform(delete("/system/oper-logs/clean")).andExpect(status().isOk());

        verify(operLogService).cleanAll();
    }
}
