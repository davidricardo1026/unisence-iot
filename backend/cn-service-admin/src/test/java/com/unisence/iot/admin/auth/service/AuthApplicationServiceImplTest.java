package com.unisence.iot.admin.auth.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.auth.config.AuthConfigProperties;
import com.unisence.iot.admin.auth.dto.LoginRequest;
import com.unisence.iot.admin.auth.dto.LoginVO;
import com.unisence.iot.admin.auth.event.LoginLogEvent;
import com.unisence.iot.admin.auth.strategy.LoginStrategy;
import com.unisence.iot.admin.auth.strategy.LoginStrategyFactory;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.system.service.SysUserCacheService;
import com.unisence.iot.admin.system.service.SysUserOnlineService;
import com.unisence.iot.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link AuthApplicationServiceImpl} 编排层测试。
 * <p>
 * mock 全部协作方(渠道配置 / 策略工厂 / 用户缓存 / 事件发布器),
 * Sa-Token 的 {@link StpUtil} 静态调用用 {@code mockStatic} 隔离,不碰真实上下文/DB/Redis。
 */
class AuthApplicationServiceImplTest {

    private static final Map<String, String> LOCAL_PARAMS =
        Map.of("userCode", "admin", "password", "deadbeef");

    private AuthConfigProperties authConfigProperties;
    private LoginStrategyFactory loginStrategyFactory;
    private SysUserCacheService userCacheService;
    private ApplicationEventPublisher eventPublisher;
    private AuthApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        authConfigProperties = mock(AuthConfigProperties.class);
        loginStrategyFactory = mock(LoginStrategyFactory.class);
        userCacheService = mock(SysUserCacheService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new AuthApplicationServiceImpl(
            authConfigProperties, loginStrategyFactory, userCacheService, eventPublisher);
    }

    @Test
    @DisplayName("登录渠道未开启 → 403/1003,短路不取策略,并记失败日志")
    void login_channelDisabled_forbidden() {
        when(authConfigProperties.getEnabledTypes()).thenReturn(List.of("local"));

        assertThatThrownBy(() -> service.login(loginRequest("sms", LOCAL_PARAMS), servletRequest()))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(ex.getCode()).isEqualTo(1003);
            });

        verify(loginStrategyFactory, never()).getStrategy(anyString());
        assertThat(capturedLog().getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("认证通过但用户主体不存在 → 401/1002,并记失败日志")
    void login_userEntityMissing_unauthorized() {
        when(authConfigProperties.getEnabledTypes()).thenReturn(List.of("local"));
        LoginStrategy strategy = mock(LoginStrategy.class);
        when(loginStrategyFactory.getStrategy("local")).thenReturn(strategy);
        when(strategy.authenticate(any())).thenReturn(2L);
        when(userCacheService.getById(2L)).thenReturn(null);

        assertThatThrownBy(() -> service.login(loginRequest("local", LOCAL_PARAMS), servletRequest()))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getCode()).isEqualTo(1002);
            });

        assertThat(capturedLog().getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("登录成功 → 签发 token、StpUtil.login、写在线会话、记成功日志")
    void login_success_issuesTokenAndFillsSession() {
        when(authConfigProperties.getEnabledTypes()).thenReturn(List.of("local"));
        LoginStrategy strategy = mock(LoginStrategy.class);
        when(loginStrategyFactory.getStrategy("local")).thenReturn(strategy);
        when(strategy.authenticate(any())).thenReturn(2L);

        SysUser user = new SysUser();
        user.setUserId(2L);
        user.setUserCode("admin");
        user.setUserName("管理员");
        user.setStatus(1);
        when(userCacheService.getById(2L)).thenReturn(user);

        HttpServletRequest req = servletRequest();
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("User-Agent")).thenReturn("JUnit-UA");

        SaSession session = mock(SaSession.class);

        LoginVO vo;
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getTokenSession).thenReturn(session);
            stp.when(StpUtil::getTokenValue).thenReturn("tok-xyz");

            vo = service.login(loginRequest("local", LOCAL_PARAMS), req);

            stp.verify(() -> StpUtil.login(2L));
        }

        assertThat(vo.getToken()).isEqualTo("tok-xyz");
        // 在线会话写入用户身份
        verify(session).set(SysUserOnlineService.SESSION_KEY_USER_CODE, "admin");
        verify(session).set(SysUserOnlineService.SESSION_KEY_USER_NAME, "管理员");
        // 成功日志 status=1
        assertThat(capturedLog().getStatus()).isEqualTo(1);
    }

    // ---------- helpers ----------

    private static LoginRequest loginRequest(String identityType, Map<String, String> authParams) {
        LoginRequest r = new LoginRequest();
        r.setIdentityType(identityType);
        r.setAuthParams(authParams);
        return r;
    }

    private static HttpServletRequest servletRequest() {
        return mock(HttpServletRequest.class);
    }

    /**
     * 捕获发布的登录日志事件里的 SysLoginLog(每个用例恰好发布一次)
     */
    private com.unisence.iot.admin.entity.SysLoginLog capturedLog() {
        ArgumentCaptor<LoginLogEvent> captor = ArgumentCaptor.forClass(LoginLogEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue().getLog();
    }
}
