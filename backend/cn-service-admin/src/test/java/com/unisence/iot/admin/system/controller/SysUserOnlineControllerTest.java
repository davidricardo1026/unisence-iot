package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.UserOnlineQuery;
import com.unisence.iot.admin.system.service.SysUserOnlineService;
import com.unisence.iot.admin.system.vo.SysUserOnlineVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageRequestArgumentResolver;
import com.unisence.iot.common.api.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * {@link SysUserOnlineController} Web 层测试（standaloneSetup）。
 * 覆盖：在线列表分页参数绑定 + 透传、强退按路径 tokenId 透传。
 */
class SysUserOnlineControllerTest {

    private SysUserOnlineService onlineService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        onlineService = mock(SysUserOnlineService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysUserOnlineController(onlineService))
            .setCustomArgumentResolvers(new PageRequestArgumentResolver())
            .build();
    }

    @Test
    @DisplayName("GET /system/online/list：绑定 pageNum/pageSize/query.userCode 并透传")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void list_bindsPageParamsAndDelegates() throws Exception {
        SysUserOnlineVO vo = new SysUserOnlineVO();
        vo.setTokenId("uuid-a");
        vo.setUserCode("admin");
        when(onlineService.listOnlineUsers(any())).thenReturn(new PageResult<>(List.of(vo), 1L));

        mockMvc.perform(get("/system/online/list")
                            .param("pageNum", "2")
                            .param("pageSize", "5")
                            .param("query.userCode", "adm"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.list[0].tokenId").value("uuid-a"));

        ArgumentCaptor<PageRequest<UserOnlineQuery>> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(onlineService).listOnlineUsers(captor.capture());
        PageRequest<UserOnlineQuery> req = captor.getValue();
        assertThat(req.getPageNum()).isEqualTo(2);
        assertThat(req.getPageSize()).isEqualTo(5);
        assertThat(req.getQuery().getUserCode()).isEqualTo("adm");
    }

    @Test
    @DisplayName("DELETE /system/online/{tokenId}：按路径 token 强退")
    void kickout_delegatesPathVar() throws Exception {
        mockMvc.perform(delete("/system/online/uuid-x"))
            .andExpect(status().isOk());

        verify(onlineService).kickout("uuid-x");
    }
}
