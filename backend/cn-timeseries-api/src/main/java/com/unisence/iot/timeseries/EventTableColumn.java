package com.unisence.iot.timeseries;

import com.unisence.iot.rule.sdk.PropertyDataType;

import java.util.Locale;
import java.util.Objects;

public record EventTableColumn(String paramIdentifier, PropertyDataType dataType) {

    public EventTableColumn {
        Objects.requireNonNull(dataType, "dataType");
        EventTableNames.requireIdentifier(paramIdentifier);
        paramIdentifier = paramIdentifier.toLowerCase(Locale.ROOT);
        EventTableNames.columnName(paramIdentifier);
    }
}
