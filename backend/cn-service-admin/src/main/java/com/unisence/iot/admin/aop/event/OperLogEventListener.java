package com.unisence.iot.admin.aop.event;

import com.unisence.iot.admin.config.AsyncConfig;
import com.unisence.iot.admin.mapper.SysOperLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OperLogEventListener {

    private final SysOperLogMapper operLogMapper;

    @EventListener
    @Async(AsyncConfig.NORMAL_EXECUTOR)
    public void onOperLogEvent(OperLogEvent event) {
        try {
            operLogMapper.insert(event.getLog());
        } catch (Exception e) {
            log.warn("操作日志写入失败", e);
        }
    }
}
