package com.unisence.iot.admin.aop.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public final class JsonSerializeUtil {

    private static final String TRUNCATED_SUFFIX = "...(truncated)";

    private JsonSerializeUtil() {
    }

    public static String serialize(ObjectMapper mapper, Object obj, int limit) {
        int writeLimit = limit - TRUNCATED_SUFFIX.length();
        LimitedWriter writer = new LimitedWriter(writeLimit);
        try {
            mapper.writeValue(writer, obj);
            return writer.getWritten();
        } catch (LimitExceededException e) {
            return writer.getWritten() + TRUNCATED_SUFFIX;
        } catch (IOException e) {
            log.warn("对象序列化失败: {}", e.getMessage(), e);
            return "";
        }
    }
}
