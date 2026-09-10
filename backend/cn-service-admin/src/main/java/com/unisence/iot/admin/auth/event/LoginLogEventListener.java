package com.unisence.iot.admin.auth.event;

import com.unisence.iot.admin.config.AsyncConfig;
import com.unisence.iot.admin.mapper.SysLoginLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoginLogEventListener {

    private final SysLoginLogMapper loginLogMapper;

    @EventListener
    @Async(AsyncConfig.NORMAL_EXECUTOR)
    public void onLoginLogEvent(LoginLogEvent event) {
        try {
            loginLogMapper.insert(event.getLog());
        } catch (Exception e) {
            log.warn("登录日志写入失败", e);
        }
    }
}
