package com.unisence.iot.admin.auth.event;

import com.unisence.iot.admin.entity.SysLoginLog;
import org.springframework.context.ApplicationEvent;

public class LoginLogEvent extends ApplicationEvent {

    private final SysLoginLog log;

    public LoginLogEvent(Object source, SysLoginLog log) {
        super(source);
        this.log = log;
    }

    public SysLoginLog getLog() {
        return log;
    }
}
