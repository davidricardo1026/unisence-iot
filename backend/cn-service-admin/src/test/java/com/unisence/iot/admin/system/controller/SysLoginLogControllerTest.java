package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.LoginLogQuery;
import com.unisence.iot.admin.system.service.SysLoginLogService;
import com.unisence.iot.admin.system.vo.LoginLogVO;
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
 * {@link SysLoginLogController} Web 层测试（standaloneSetup）。
 * 覆盖：分页绑定+透传、单条删除路径透传、批量删除请求体透传、整表清空。
 */
class SysLoginLogControllerTest {

    private SysLoginLogService loginLogService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        loginLogService = mock(SysLoginLogService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysLoginLogController(loginLogService))
            .setCustomArgumentResolvers(new PageRequestArgumentResolver())
            .build();
    }

    @Test
    @DisplayName("GET /system/login-logs：分页参数绑定并透传")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void page_bindsAndDelegates() throws Exception {
        LoginLogVO vo = new LoginLogVO();
        vo.setLoginLogId(3L);
        when(loginLogService.pageLoginLogs(any())).thenReturn(new PageResult<>(List.of(vo), 1L));

        mockMvc.perform(get("/system/login-logs")
                            .param("pageNum", "1").param("pageSize", "10")
                            .param("query.userCode", "admin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.list[0].loginLogId").value(3));

        ArgumentCaptor<PageRequest<LoginLogQuery>> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(loginLogService).pageLoginLogs(captor.capture());
        assertThat(captor.getValue().getQuery().getUserCode()).isEqualTo("admin");
    }

    @Test
    @DisplayName("DELETE /system/login-logs/{id}：按路径 id 单条删除")
    void deleteById_delegatesPathId() throws Exception {
        mockMvc.perform(delete("/system/login-logs/9")).andExpect(status().isOk());

        verify(loginLogService).deleteById(9L);
    }

    @Test
    @DisplayName("DELETE /system/login-logs：请求体 id 列表透传批量删除")
    void batchDelete_bodyIds() throws Exception {
        mockMvc.perform(delete("/system/login-logs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[4,5]"))
            .andExpect(status().isOk());

        verify(loginLogService).batchDelete(List.of(4L, 5L));
    }

    @Test
    @DisplayName("DELETE /system/login-logs/clean：整表清空")
    void cleanAll_delegates() throws Exception {
        mockMvc.perform(delete("/system/login-logs/clean")).andExpect(status().isOk());

        verify(loginLogService).cleanAll();
    }
}
