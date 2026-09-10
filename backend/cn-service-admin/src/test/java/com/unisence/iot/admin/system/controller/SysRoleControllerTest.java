package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.RoleCreateRequest;
import com.unisence.iot.admin.system.dto.RoleQuery;
import com.unisence.iot.admin.system.dto.RoleUpdateRequest;
import com.unisence.iot.admin.system.service.SysRoleService;
import com.unisence.iot.admin.system.vo.RoleVO;
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
 * {@link SysRoleController} Web 层测试。
 * <p>
 * 同 {@code SysUserControllerTest}：standaloneSetup + mock service + 真实 {@link PageRequestArgumentResolver}，
 * 不加载上下文/DB，断言原始返回体与 {@code @Valid} 校验。
 */
class SysRoleControllerTest {

    private SysRoleService roleService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        roleService = mock(SysRoleService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysRoleController(roleService))
            .setCustomArgumentResolvers(new PageRequestArgumentResolver())
            .build();
    }

    @Test
    @DisplayName("GET /system/roles：透传分页与 query.* 并返回列表")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listRoles_bindsPageParamsAndReturnsList() throws Exception {
        RoleVO vo = new RoleVO();
        vo.setRoleId(3L);
        vo.setRoleName("运维");
        vo.setRoleCode("ops");
        when(roleService.pageRoles(any())).thenReturn(new PageResult<>(List.of(vo), 1L));

        mockMvc.perform(get("/system/roles")
                            .param("pageNum", "2")
                            .param("pageSize", "10")
                            .param("query.roleName", "运维"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.list[0].roleId").value(3))
            .andExpect(jsonPath("$.list[0].roleCode").value("ops"));

        ArgumentCaptor<PageRequest<RoleQuery>> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(roleService).pageRoles(captor.capture());
        PageRequest<RoleQuery> captured = captor.getValue();
        assertThat(captured.getPageNum()).isEqualTo(2);
        assertThat(captured.getPageSize()).isEqualTo(10);
        assertThat(captured.getQuery().getRoleName()).isEqualTo("运维");
    }

    @Test
    @DisplayName("GET /system/roles/{id}：按路径 id 返回单个角色")
    void getRole_returnsSingle() throws Exception {
        RoleVO vo = new RoleVO();
        vo.setRoleId(5L);
        vo.setRoleCode("ops");
        when(roleService.getRole(5L)).thenReturn(vo);

        mockMvc.perform(get("/system/roles/5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.roleId").value(5))
            .andExpect(jsonPath("$.roleCode").value("ops"));

        verify(roleService).getRole(5L);
    }

    @Test
    @DisplayName("POST /system/roles：合法请求透传并返回自增 roleId")
    void createRole_valid_returnsGeneratedId() throws Exception {
        when(roleService.createRole(any(RoleCreateRequest.class))).thenReturn(50L);

        String body = "{\"roleName\":\"运维\",\"roleCode\":\"ops\",\"status\":1}";

        mockMvc.perform(post("/system/roles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("50"));

        ArgumentCaptor<RoleCreateRequest> captor = ArgumentCaptor.forClass(RoleCreateRequest.class);
        verify(roleService).createRole(captor.capture());
        assertThat(captor.getValue().getRoleName()).isEqualTo("运维");
        assertThat(captor.getValue().getRoleCode()).isEqualTo("ops");
    }

    @Test
    @DisplayName("POST /system/roles：roleName 为空 → 400，且不进 service")
    void createRole_blankRoleName_badRequest() throws Exception {
        String body = "{\"roleName\":\"\",\"roleCode\":\"ops\"}";

        mockMvc.perform(post("/system/roles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(roleService, never()).createRole(any());
    }

    @Test
    @DisplayName("PUT /system/roles/{id}：路径 id 与请求体一起透传")
    void updateRole_valid_delegatesWithPathId() throws Exception {
        String body = "{\"roleName\":\"运维改\",\"roleCode\":\"ops\",\"status\":1,\"version\":0}";

        mockMvc.perform(put("/system/roles/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<RoleUpdateRequest> captor = ArgumentCaptor.forClass(RoleUpdateRequest.class);
        verify(roleService).updateRole(eq(7L), captor.capture());
        assertThat(captor.getValue().getRoleName()).isEqualTo("运维改");
        assertThat(captor.getValue().getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("PUT /system/roles/{id}：缺少乐观锁 version → 400，且不进 service")
    void updateRole_missingVersion_badRequest() throws Exception {
        String body = "{\"roleName\":\"运维改\",\"roleCode\":\"ops\",\"status\":1}";

        mockMvc.perform(put("/system/roles/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(roleService, never()).updateRole(any(), any());
    }

    @Test
    @DisplayName("DELETE /system/roles/{id}：按路径 id 透传删除")
    void deleteRole_delegatesWithPathId() throws Exception {
        mockMvc.perform(delete("/system/roles/9"))
            .andExpect(status().isOk());

        verify(roleService).deleteRole(9L);
    }
}
