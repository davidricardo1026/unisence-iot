package com.unisence.iot.admin.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.auth.config.AuthConfigProperties;
import com.unisence.iot.admin.auth.dto.LoginRequest;
import com.unisence.iot.admin.auth.dto.LoginVO;
import com.unisence.iot.admin.auth.service.AuthApplicationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
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
 * {@link AuthController} Web 层测试。
 * <p>
 * standaloneSetup + mock 协作方:不加载 Spring 上下文/DB/Redis/Sa-Token 运行时,
 * 断言 Controller 的原始返回体与 {@code @Valid} 参数校验行为。
 * logout 的 {@link StpUtil} 静态调用用 {@code mockStatic} 隔离。
 */
class AuthControllerTest {

    private AuthConfigProperties authConfigProperties;
    private AuthApplicationService authApplicationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authConfigProperties = mock(AuthConfigProperties.class);
        authApplicationService = mock(AuthApplicationService.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new AuthController(authConfigProperties, authApplicationService))
            .build();
    }

    @Test
    @DisplayName("GET /auth/enabled-types:返回已启用登录渠道列表")
    void enabledTypes_returnsConfiguredChannels() throws Exception {
        when(authConfigProperties.getEnabledTypes()).thenReturn(List.of("local"));

        mockMvc.perform(get("/auth/enabled-types"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0]").value("local"));
    }

    @Test
    @DisplayName("POST /auth/login:合法请求透传给应用服务并返回 token")
    void login_valid_delegatesAndReturnsToken() throws Exception {
        when(authApplicationService.login(any(LoginRequest.class), any(HttpServletRequest.class)))
            .thenReturn(new LoginVO("tok-123"));

        String body = "{\"identityType\":\"local\","
            + "\"authParams\":{\"userCode\":\"admin\",\"password\":\"deadbeef\"}}";

        mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("tok-123"));

        ArgumentCaptor<LoginRequest> captor = ArgumentCaptor.forClass(LoginRequest.class);
        verify(authApplicationService).login(captor.capture(), any(HttpServletRequest.class));
        LoginRequest passed = captor.getValue();
        assertThat(passed.getIdentityType()).isEqualTo("local");
        assertThat(passed.getAuthParams()).containsEntry("userCode", "admin");
    }

    @Test
    @DisplayName("POST /auth/login:identityType 为空 → 400,且不进应用服务")
    void login_blankIdentityType_badRequest() throws Exception {
        String body = "{\"identityType\":\"\",\"authParams\":{}}";

        mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(authApplicationService, never()).login(any(), any());
    }

    @Test
    @DisplayName("POST /auth/login:authParams 缺失 → 400,且不进应用服务")
    void login_missingAuthParams_badRequest() throws Exception {
        String body = "{\"identityType\":\"local\"}";

        mockMvc.perform(post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
            .andExpect(status().isBadRequest());

        verify(authApplicationService, never()).login(any(), any());
    }

    @Test
    @DisplayName("POST /auth/logout:调用 StpUtil.logout()")
    void logout_invokesStpUtilLogout() throws Exception {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk());

            stp.verify(StpUtil::logout);
        }
    }
}
