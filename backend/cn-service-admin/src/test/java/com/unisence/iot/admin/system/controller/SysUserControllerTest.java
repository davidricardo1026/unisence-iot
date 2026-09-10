package com.unisence.iot.admin.system.controller;

import com.unisence.iot.admin.system.dto.ResetPasswordRequest;
import com.unisence.iot.admin.system.dto.UserCreateRequest;
import com.unisence.iot.admin.system.dto.UserQuery;
import com.unisence.iot.admin.system.dto.UserUpdateRequest;
import com.unisence.iot.admin.system.service.SysUserService;
import com.unisence.iot.admin.system.vo.UserVO;
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
 * {@link SysUserController} Web 层测试。
 * <p>
 * 采用 MockMvc {@code standaloneSetup}：只装被测 Controller 和被 mock 的 Service，
 * 手动挂上真实的 {@link PageRequestArgumentResolver}。不加载 Spring 上下文，
 * 因此不依赖 Redis / MySQL / Sa-Token 运行时，快速且必绿。
 * <p>
 * 说明：本切片刻意不引入 {@code SaTokenConfig}（鉴权拦截器 / {@code @SaCheckPermission}）
 * 与 {@code ResultEasyTransAdvice}（统一 {@code Result} 包装 + EasyTrans 翻译），
 * 因此断言的是 Controller 的<b>原始返回体</b>。鉴权与 Result 包装作为后续独立测试补充。
 */
class SysUserControllerTest {

    private SysUserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(SysUserService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SysUserController(userService))
            .setCustomArgumentResolvers(new PageRequestArgumentResolver())
            .build();
    }

    // ---------- 查询 ----------

    @Test
    @DisplayName("GET /system/users：透传 pageNum/pageSize/query.* 并返回分页列表")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listUsers_bindsPageParamsAndReturnsList() throws Exception {
        UserVO vo = new UserVO();
        vo.setUserId(2L);
        vo.setUserCode("admin");
        vo.setUserName("管理员");
        vo.setRoleNames(List.of("系统管理员"));
        // phone / 时间字段留 null：避开 @Sensitive 序列化器与 JSR-310
        when(userService.pageUsers(any())).thenReturn(new PageResult<>(List.of(vo), 1L));

        mockMvc.perform(get("/system/users")
                            .param("pageNum", "2")
                            .param("pageSize", "10")
                            .param("query.userCode", "admin"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.list[0].userId").value(2))
            .andExpect(jsonPath("$.list[0].userCode").value("admin"))
            .andExpect(jsonPath("$.list[0].roleNames[0]").value("系统管理员"));

        // 校验解析器把分页参数与 query. 前缀正确绑进 PageRequest<UserQuery>
        ArgumentCaptor<PageRequest<UserQuery>> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(userService).pageUsers(captor.capture());
        PageRequest<UserQuery> captured = captor.getValue();
        assertThat(captured.getPageNum()).isEqualTo(2);
        assertThat(captured.getPageSize()).isEqualTo(10);
        assertThat(captured.getQuery()).isNotNull();
        assertThat(captured.getQuery().getUserCode()).isEqualTo("admin");
    }

    @Test
    @DisplayName("GET /system/users：未传分页参数时回落到默认 1/20")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listUsers_defaultsPagingWhenAbsent() throws Exception {
        when(userService.pageUsers(any())).thenReturn(new PageResult<>(List.of(), 0L));

        mockMvc.perform(get("/system/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));

        ArgumentCaptor<PageRequest<UserQuery>> captor = ArgumentCaptor.forClass(PageRequest.class);
        verify(userService).pageUsers(captor.capture());
        assertThat(captor.getValue().getPageNum()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("GET /system/users/profile：返回当前登录用户信息")
    void getProfile_returnsCurrentUser() throws Exception {
        UserVO vo = new UserVO();
        vo.setUserId(1L);
        vo.setUserCode("superAdmin");
        when(userService.getUserProfile()).thenReturn(vo);

        mockMvc.perform(get("/system/users/profile"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.userId").value(1))
            .andExpect(jsonPath("$.userCode").value("superAdmin"));

        verify(userService).getUserProfile();
    }

    // ---------- 新增 ----------

    @Test
    @DisplayName("POST /system/users：合法请求透传并返回自增 userId")
    void createUser_valid_returnsGeneratedId() throws Exception {
        when(userService.createUser(any(UserCreateRequest.class))).thenReturn(100L);

        String passwordHash = "a".repeat(64);
        String body = "{\"userCode\":\"newuser\",\"userName\":\"新用户\","
            + "\"deptId\":10,\"password\":\"" + passwordHash + "\",\"status\":1}";

        mockMvc.perform(post("/system/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk())
            .andExpect(content().string("100"));

        ArgumentCaptor<UserCreateRequest> captor = ArgumentCaptor.forClass(UserCreateRequest.class);
        verify(userService).createUser(captor.capture());
        UserCreateRequest passed = captor.getValue();
        assertThat(passed.getUserCode()).isEqualTo("newuser");
        assertThat(passed.getDeptId()).isEqualTo(10L);
        assertThat(passed.getPassword()).isEqualTo(passwordHash);
    }

    @Test
    @DisplayName("POST /system/users：userCode 为空 → 400，且不进 service")
    void createUser_blankUserCode_badRequest() throws Exception {
        String body = "{\"userCode\":\"\",\"userName\":\"新用户\","
            + "\"deptId\":10,\"password\":\"deadbeef\"}";

        mockMvc.perform(post("/system/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(userService, never()).createUser(any());
    }

    // ---------- 修改 ----------

    @Test
    @DisplayName("PUT /system/users/{id}：路径 id 与请求体一起透传")
    void updateUser_valid_delegatesWithPathId() throws Exception {
        String body = "{\"userName\":\"改名\",\"deptId\":10,\"status\":1,\"version\":0}";

        mockMvc.perform(put("/system/users/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<UserUpdateRequest> captor = ArgumentCaptor.forClass(UserUpdateRequest.class);
        verify(userService).updateUser(eq(7L), captor.capture());
        UserUpdateRequest passed = captor.getValue();
        assertThat(passed.getUserName()).isEqualTo("改名");
        assertThat(passed.getVersion()).isEqualTo(0);
    }

    @Test
    @DisplayName("PUT /system/users/{id}：缺少乐观锁 version → 400，且不进 service")
    void updateUser_missingVersion_badRequest() throws Exception {
        String body = "{\"userName\":\"改名\",\"deptId\":10,\"status\":1}";

        mockMvc.perform(put("/system/users/7")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(userService, never()).updateUser(any(), any());
    }

    // ---------- 删除 ----------

    @Test
    @DisplayName("DELETE /system/users/{id}：按路径 id 透传删除")
    void deleteUser_delegatesWithPathId() throws Exception {
        mockMvc.perform(delete("/system/users/9"))
            .andExpect(status().isOk());

        verify(userService).deleteUser(9L);
    }

    // ---------- 重置密码 ----------

    @Test
    @DisplayName("PUT /system/users/{id}/reset-password：合法请求透传")
    void resetPassword_valid_delegates() throws Exception {
        String passwordHash = "b".repeat(64);
        String body = "{\"password\":\"" + passwordHash + "\"}";

        mockMvc.perform(put("/system/users/3/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<ResetPasswordRequest> captor = ArgumentCaptor.forClass(ResetPasswordRequest.class);
        verify(userService).resetPassword(eq(3L), captor.capture());
        assertThat(captor.getValue().getPassword()).isEqualTo(passwordHash);
    }

    @Test
    @DisplayName("PUT /system/users/{id}/reset-password：密码为空 → 400，且不进 service")
    void resetPassword_blankPassword_badRequest() throws Exception {
        mockMvc.perform(put("/system/users/3/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
            .andExpect(status().isBadRequest());

        verify(userService, never()).resetPassword(any(), any());
    }
}
