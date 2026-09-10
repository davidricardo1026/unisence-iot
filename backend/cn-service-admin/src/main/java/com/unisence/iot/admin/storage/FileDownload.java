package com.unisence.iot.admin.storage;

import org.springframework.core.io.Resource;

/**
 * 面向 HTTP 输出的文件内容与元信息。
 */
public record FileDownload(
    Resource resource,
    String filename,
    String contentType,
    long size
) {
}
