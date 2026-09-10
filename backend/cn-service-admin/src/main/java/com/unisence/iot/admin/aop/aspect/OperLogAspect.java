package com.unisence.iot.admin.aop.aspect;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.aop.event.OperLogEvent;
import com.unisence.iot.admin.aop.util.JsonSerializeUtil;
import com.unisence.iot.admin.entity.SysOperLog;
import com.unisence.iot.admin.system.service.SysUserOnlineService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperLogAspect {

    private static final int MAX_PARAM_LENGTH = 65535;
    private static final int MAX_RESULT_LENGTH = 65535;

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Around("@annotation(operLog)")
    public Object around(ProceedingJoinPoint pjp, OperLog operLog) throws Throwable {
        Object result = null;
        Exception ex = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Exception e) {
            ex = e;
            throw e;
        } finally {
            try {
                publishLog(pjp, operLog, result, ex);
            } catch (Exception ignored) {
                log.warn("操作日志发布失败", ignored);
            }
        }
    }

    private void publishLog(ProceedingJoinPoint pjp, OperLog operLog,
                            Object result, Exception ex) {
        SysOperLog sysOperLog = new SysOperLog();
        MethodSignature sig = (MethodSignature) pjp.getSignature();

        sysOperLog.setTitle(operLog.title());
        sysOperLog.setBusinessType(operLog.businessType().getCode());
        sysOperLog.setMethod(pjp.getTarget().getClass().getName() + "." + sig.getName());
        sysOperLog.setStatus(ex == null ? 1 : 0);

        if (ex != null) {
            String msg = ex.getMessage();
            sysOperLog.setErrorMsg(msg != null && msg.length() > 2000 ? msg.substring(0, 2000) : msg);
        }

        // 操作人：从 Sa-Token TokenSession 读取已缓存的 userCode
        try {
            if (StpUtil.isLogin()) {
                sysOperLog.setOperatorCode(StpUtil.getTokenSession().getString(SysUserOnlineService.SESSION_KEY_USER_CODE));
            }
        } catch (Exception ignored) {
            log.error("获取operatorCode失败", ignored);
        }

        // HTTP 上下文
        ServletRequestAttributes attrs =
            (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            sysOperLog.setOperIp(request.getRemoteAddr());
            sysOperLog.setOperUrl(request.getRequestURI());
            sysOperLog.setRequestMethod(request.getMethod());
        }

        // 请求参数
        if (operLog.saveRequestData()) {
            sysOperLog.setOperParam(buildParamJson(pjp.getArgs()));
        }

        // 响应体
        if (operLog.saveResponseData() && result != null) {
            sysOperLog.setJsonResult(JsonSerializeUtil.serialize(objectMapper, result, MAX_RESULT_LENGTH));
        }

        eventPublisher.publishEvent(new OperLogEvent(this, sysOperLog));
    }

    private String buildParamJson(Object[] args) {
        List<Object> filtered = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof HttpServletRequest
                || arg instanceof HttpServletResponse
                || arg instanceof MultipartFile) {
                continue;
            }
            filtered.add(arg);
        }
        Object target = filtered.size() == 1 ? filtered.get(0) : filtered;
        return JsonSerializeUtil.serialize(objectMapper, target, MAX_PARAM_LENGTH);
    }

}
