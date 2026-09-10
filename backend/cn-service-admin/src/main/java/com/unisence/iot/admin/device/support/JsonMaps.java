package com.unisence.iot.admin.device.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisence.iot.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class JsonMaps {

    private final ObjectMapper objectMapper;

    public JsonMaps(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("JSON 序列化失败", e);
            throw new BusinessException(HttpStatus.BAD_REQUEST, 1001, "JSON 序列化失败");
        }
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            log.error("JSON 对象反序列化失败", e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 1001, "JSON 反序列化失败");
        }
    }

    public <T> List<T> readList(String json, Class<T> elementType) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json,
                                          objectMapper.getTypeFactory().constructCollectionType(List.class,
                                                                                                elementType));
        } catch (JsonProcessingException e) {
            log.error("JSON 数组反序列化失败: elementType={}", elementType.getName(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 1001, "JSON 数组反序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> readMapList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException e) {
            log.error("JSON Map 数组反序列化失败", e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 1001, "JSON 数组反序列化失败");
        }
    }
}
