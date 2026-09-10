package com.unisence.iot.admin.display.advice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.api.Result;
import io.github.easytrans.core.spi.TranslationExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@ControllerAdvice(basePackages = {
    "com.unisence.iot.admin",
    "com.unisence.iot.driver"
})
@RequiredArgsConstructor
public class ResultEasyTransAdvice implements ResponseBodyAdvice<Object> {

    private final TranslationExecutor translationExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        Class<?> paramType = returnType.getParameterType();
        // 如果返回 ResponseEntity，不进行重复包装
        if (ResponseEntity.class.isAssignableFrom(paramType)) {
            return false;
        }
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        // 1. 如果 body 已经是 Result 格式，说明可能是异常处理器返回，或者旧代码包装，只需执行翻译
        if (body instanceof Result<?> result) {
            Object data = result.data();
            if (data != null) {
                unwrapAndTranslate(data);
            }
            return body;
        }

        // 2. 执行翻译（如果 body 不为 null）
        if (body != null) {
            unwrapAndTranslate(body);
        }

        // 3. 特殊处理 String 返回类型，防止 StringHttpMessageConverter 抛出 ClassCastException
        if (body instanceof String str) {
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            try {
                return objectMapper.writeValueAsString(Result.ok(str));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to wrap String response", e);
            }
        }

        // 4. 自动包装：将原始对象自动包装为标准的 Result 格式返回给前端
        return Result.ok(body);
    }

    private void unwrapAndTranslate(Object data) {
        if (data instanceof PageResult<?> page) {
            translateIfTranslatable(page.list());
            return;
        }
        if (data instanceof List<?> list) {
            translateIfTranslatable(list);
            return;
        }
        translateIfTranslatable(List.of(data));
    }

    private void translateIfTranslatable(List<?> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Object firstItem = list.get(0);
        if (firstItem == null || isSkippedType(firstItem.getClass())) {
            return;
        }

        // 调用 EasyTrans 就地转义引擎
        translationExecutor.translate(list);
    }

    private static boolean isSkippedType(Class<?> type) {
        return type.isPrimitive()
            || type.getPackageName().startsWith("java.")
            || Map.class.isAssignableFrom(type)
            || Collection.class.isAssignableFrom(type);
    }
}
