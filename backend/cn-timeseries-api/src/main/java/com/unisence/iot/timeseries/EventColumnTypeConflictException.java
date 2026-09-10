package com.unisence.iot.timeseries;

/**
 * 同一 identifier 的已落地列与请求 dataType 不一致。Admin 映射为错误码 3026，且不得发 DDL。
 */
public class EventColumnTypeConflictException extends RuntimeException {

    private final String productKey;
    private final String identifier;
    private final String paramIdentifier;
    private final String existingType;
    private final String requestedType;

    public EventColumnTypeConflictException(String productKey, String identifier, String paramIdentifier,
                                            String existingType, String requestedType) {
        super("禁止修改已落地事件参数列的类型: productKey=" + productKey
                  + " identifier=" + identifier + " param=" + paramIdentifier
                  + " existing=" + existingType + " requested=" + requestedType);
        this.productKey = productKey;
        this.identifier = identifier;
        this.paramIdentifier = paramIdentifier;
        this.existingType = existingType;
        this.requestedType = requestedType;
    }

    public String productKey() {
        return productKey;
    }

    public String identifier() {
        return identifier;
    }

    public String paramIdentifier() {
        return paramIdentifier;
    }

    public String existingType() {
        return existingType;
    }

    public String requestedType() {
        return requestedType;
    }
}
