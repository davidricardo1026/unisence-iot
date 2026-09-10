package com.unisence.iot.timeseries;

import com.unisence.iot.rule.sdk.PropertyDataType;

import java.util.Locale;
import java.util.Objects;

public record EventParamValue(String identifier, PropertyDataType dataType, Object value) {

    public EventParamValue {
        Objects.requireNonNull(dataType, "dataType");
        EventTableNames.requireIdentifier(identifier);
        identifier = identifier.toLowerCase(Locale.ROOT);
        EventTableNames.columnName(identifier);
    }
}
