package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.storage.FileDownload;
import com.unisence.iot.admin.system.dto.FileQuery;
import com.unisence.iot.admin.system.service.SysFileInfoService;
import com.unisence.iot.admin.system.vo.FileInfoVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/system/files")
@RequiredArgsConstructor
public class SysFileInfoController {

    private final SysFileInfoService fileInfoService;

    @GetMapping
    @SaCheckPermission("sys:file:list")
    public PageResult<FileInfoVO> pageFiles(PageRequest<FileQuery> request) {
        return fileInfoService.pageFiles(request);
    }

    @PostMapping
    @SaCheckPermission("sys:file:upload")
    @OperLog(title = "文件管理", businessType = BusinessType.INSERT)
    public FileInfoVO upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "folder", defaultValue = "general") String folder) {
        return fileInfoService.upload(file, folder);
    }

    /**
     * 图片、头像等公开媒体使用稳定的业务 URL 访问，不暴露底层存储对象键。
     */
    @GetMapping("/{fileInfoId}/content")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long fileInfoId) {
        FileDownload file = fileInfoService.downloadFile(fileInfoId);
        MediaType mediaType = resolveMediaType(file.contentType());
        boolean inline = "image".equalsIgnoreCase(mediaType.getType())
            && !"svg+xml".equalsIgnoreCase(mediaType.getSubtype());
        ContentDisposition disposition = inline
            ? ContentDisposition.inline().filename(file.filename(), StandardCharsets.UTF_8).build()
            : ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
            .contentType(mediaType)
            .contentLength(file.size())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(file.resource());
    }

    @DeleteMapping("/{fileInfoId}")
    @SaCheckPermission("sys:file:delete")
    @OperLog(title = "文件管理", businessType = BusinessType.DELETE)
    public void deleteFile(@PathVariable Long fileInfoId) {
        fileInfoService.deleteFile(fileInfoId);
    }

    @DeleteMapping
    @SaCheckPermission("sys:file:delete")
    @OperLog(title = "文件管理", businessType = BusinessType.DELETE)
    public void batchDelete(@RequestBody List<Long> ids) {
        fileInfoService.batchDelete(ids);
    }

    private MediaType resolveMediaType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
