package com.unisence.iot.rule.sdk;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 产品物模型只读快照，按 identifier 索引。
 */
public record ThingModelSnapshot(
    Map<String, PropertyDefinition> properties,
    Map<String, EventDefinition> events) {

    public static final ThingModelSnapshot EMPTY = new ThingModelSnapshot(Map.of(), Map.of());

    public ThingModelSnapshot {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        events = events == null ? Map.of() : Map.copyOf(events);
    }

    /**
     * 未定义时返回 null。
     */
    public PropertyDefinition property(String identifier) {
        return properties.get(identifier);
    }

    /**
     * 未定义时返回 null。
     */
    public EventDefinition event(String identifier) {
        if (identifier == null) {
            return null;
        }
        EventDefinition exact = events.get(identifier);
        if (exact != null) {
            return exact;
        }
        return events.get(identifier.toLowerCase(Locale.ROOT));
    }

    public boolean hasProperty(String identifier) {
        return properties.containsKey(identifier);
    }

    public boolean hasEvent(String identifier) {
        return event(identifier) != null;
    }

    public Set<String> propertyIdentifiers() {
        return properties.keySet();
    }

    public Set<String> eventIdentifiers() {
        return events.keySet();
    }
}
