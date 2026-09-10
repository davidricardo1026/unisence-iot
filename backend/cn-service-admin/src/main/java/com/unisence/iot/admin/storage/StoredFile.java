package com.unisence.iot.admin.storage;

/**
 * 存储介质写入后的稳定对象信息。
 */
public record StoredFile(
    String objectKey,
    String originalFilename,
    String contentType,
    long size,
    String checksum
) {
}
