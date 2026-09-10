package com.unisence.iot.common.api;

public record PageResult<T>(java.util.List<T> list, long total) {
}
