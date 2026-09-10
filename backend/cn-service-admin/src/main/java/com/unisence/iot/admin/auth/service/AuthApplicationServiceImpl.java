package com.unisence.iot.admin.auth.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.unisence.iot.admin.auth.config.AuthConfigProperties;
import com.unisence.iot.admin.auth.dto.LoginRequest;
import com.unisence.iot.admin.auth.dto.LoginVO;
import com.unisence.iot.admin.auth.event.LoginLogEvent;
import com.unisence.iot.admin.auth.strategy.LoginStrategy;
import com.unisence.iot.admin.auth.strategy.LoginStrategyFactory;
import com.unisence.iot.admin.entity.SysLoginLog;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.system.service.SysUserCacheService;
import com.unisence.iot.admin.system.service.SysUserOnlineService;
import com.unisence.iot.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthApplicationServiceImpl implements AuthApplicationService {

    private final AuthConfigProperties authConfigProperties;
    private final LoginStrategyFactory loginStrategyFactory;
    private final SysUserCacheService userCacheService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public LoginVO login(LoginRequest request, HttpServletRequest servletRequest) {
        String identityType = request.getIdentityType();
        String username = request.getAuthParams() != null ? request.getAuthParams().get("userCode") : null;

        List<String> enabledTypes = authConfigProperties.getEnabledTypes();
        if (!enabledTypes.contains(identityType)) {
            saveLoginLog(username, servletRequest, 0, "该登录渠道已被系统禁用");
            throw new BusinessException(HttpStatus.FORBIDDEN, 1003, "当前登录渠道未开启: " + identityType);
        }

        try {
            LoginStrategy strategy = loginStrategyFactory.getStrategy(identityType);
            Long userId = strategy.authenticate(request.getAuthParams());

            SysUser user = userCacheService.getById(userId);
            if (user == null) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, 1002, "用户主体不存在");
            }

            StpUtil.login(userId);
            fillOnlineSession(user, servletRequest);

            saveLoginLog(username, servletRequest, 1, "登录成功");

            return new LoginVO(StpUtil.getTokenValue());
        } catch (BusinessException e) {
            saveLoginLog(username, servletRequest, 0, e.getMessage());
            throw e;
        } catch (Exception e) {
            saveLoginLog(username, servletRequest, 0, "系统异常: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 在线用户信息写入 TokenSession（Redis），供在线用户管理页查询展示
     */
    private void fillOnlineSession(SysUser user, HttpServletRequest request) {
        SaSession tokenSession = StpUtil.getTokenSession();
        tokenSession.set(SysUserOnlineService.SESSION_KEY_USER_CODE, user.getUserCode());
        tokenSession.set(SysUserOnlineService.SESSION_KEY_USER_NAME, user.getUserName());
        tokenSession.set(SysUserOnlineService.SESSION_KEY_IP, request.getRemoteAddr());
        tokenSession.set(SysUserOnlineService.SESSION_KEY_BROWSER, resolveBrowser(request));
        tokenSession.set(SysUserOnlineService.SESSION_KEY_OS, "unknown");
        tokenSession.set(SysUserOnlineService.SESSION_KEY_LOCATION, "Local");
    }

    private String resolveBrowser(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) {
            return "unknown";
        }
        return userAgent.length() > 64 ? userAgent.substring(0, 64) : userAgent;
    }

    private void saveLoginLog(String username, HttpServletRequest request, int status, String msg) {
        SysLoginLog loginLog = new SysLoginLog();
        loginLog.setUserCode(username != null ? username : "unknown");
        loginLog.setIpaddr(request.getRemoteAddr());
        loginLog.setLoginLocation("Local");
        loginLog.setBrowser(resolveBrowser(request));
        loginLog.setOs("unknown");
        loginLog.setStatus(status);
        loginLog.setMsg(msg != null ? msg : "");
        loginLog.setLoginTime(LocalDateTime.now());
        loginLog.setCreateTime(LocalDateTime.now());
        eventPublisher.publishEvent(new LoginLogEvent(this, loginLog));
    }
}
