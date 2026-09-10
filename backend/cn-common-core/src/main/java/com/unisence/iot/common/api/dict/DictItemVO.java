package com.unisence.iot.common.api.dict;

public record DictItemVO(
    String value,
    String label,
    Integer sort,
    String cssClass
) {
}
