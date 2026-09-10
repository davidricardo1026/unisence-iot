package com.unisence.iot.timeseries;

import java.util.*;

public record EventTableSpec(
    String productKey,
    String identifier,
    List<EventTableColumn> columns,
    boolean ttlEnabled,
    Integer ttlValue,
    String ttlUnit) {

    public EventTableSpec {
        Objects.requireNonNull(productKey, "productKey");
        EventTableNames.requireIdentifier(identifier);
        identifier = identifier.toLowerCase(Locale.ROOT);
        columns = columns == null ? List.of() : List.copyOf(columns);
        Set<String> seen = new HashSet<>();
        for (EventTableColumn column : columns) {
            if (!seen.add(column.paramIdentifier().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("事件参数 identifier 重复: " + column.paramIdentifier());
            }
        }
        EventTableNames.physicalTable(productKey, identifier);
        com.unisence.iot.rule.sdk.EventDataRetention.requireValid(ttlEnabled, ttlValue, ttlUnit);
    }

    public String physicalTable() {
        return EventTableNames.physicalTable(productKey, identifier);
    }
}
