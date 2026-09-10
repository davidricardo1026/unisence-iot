package com.unisence.iot.admin.aop.sensitive;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;

public class SensitiveJsonSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private SensitiveStrategy strategy;

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null || strategy == null) {
            gen.writeString(value);
        } else {
            gen.writeString(strategy.desensitizer().apply(value));
        }
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property)
        throws JsonMappingException {
        if (property != null) {
            Sensitive sensitive = property.getAnnotation(Sensitive.class);
            if (sensitive == null) {
                sensitive = property.getContextAnnotation(Sensitive.class);
            }
            if (sensitive != null) {
                SensitiveJsonSerializer instance = new SensitiveJsonSerializer();
                instance.strategy = sensitive.strategy();
                return instance;
            }
        }
        // no annotation found — pass value through unchanged (strategy remains null)
        return this;
    }
}
