package com.unisence.iot.admin.storage;

import java.io.InputStream;

/**
 * 与存储介质无关的上传载荷；调用方负责在调用完成后关闭输入流。
 */
public record UploadCommand(
    InputStream inputStream,
    String folder,
    String originalFilename,
    String contentType,
    long size
) {
}
