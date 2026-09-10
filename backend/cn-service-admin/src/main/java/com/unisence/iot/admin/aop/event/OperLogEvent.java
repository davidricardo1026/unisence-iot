package com.unisence.iot.admin.aop.event;

import com.unisence.iot.admin.entity.SysOperLog;
import org.springframework.context.ApplicationEvent;

public class OperLogEvent extends ApplicationEvent {
    private final SysOperLog log;

    public OperLogEvent(Object source, SysOperLog log) {
        super(source);
        this.log = log;
    }

    public SysOperLog getLog() {
        return log;
    }
}
