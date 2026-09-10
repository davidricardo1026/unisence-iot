package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.SysFileInfo;
import com.unisence.iot.admin.mapper.SysFileInfoMapper;
import com.unisence.iot.admin.storage.FileDownload;
import com.unisence.iot.admin.storage.StorageService;
import com.unisence.iot.admin.storage.StoredFile;
import com.unisence.iot.admin.storage.UploadCommand;
import com.unisence.iot.admin.system.converter.SysFileInfoConverter;
import com.unisence.iot.admin.system.dto.FileQuery;
import com.unisence.iot.admin.system.vo.FileInfoVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SysFileInfoServiceImpl extends BaseServiceImpl<SysFileInfoMapper, SysFileInfo>
    implements SysFileInfoService {

    private static final Set<String> ALLOWED_FOLDERS = Set.of("general", "avatars", "product-images");

    private final SysFileInfoMapper fileInfoMapper;
    private final SysFileInfoConverter fileInfoConverter;
    private final StorageService storageService;

    @Override
    public PageResult<FileInfoVO> pageFiles(PageRequest<FileQuery> request) {
        FileQuery query = request.getQuery();
        LambdaQueryWrapper<SysFileInfo> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.like(StringUtils.hasText(query.getFileName()), SysFileInfo::getFileName, query.getFileName())
                .eq(StringUtils.hasText(query.getBucketName()), SysFileInfo::getBucketName, query.getBucketName())
                .ge(query.getBeginTime() != null, SysFileInfo::getCreateTime, query.getBeginTime())
                .le(query.getEndTime() != null, SysFileInfo::getCreateTime, query.getEndTime());
        }
        wrapper.orderByDesc(SysFileInfo::getCreateTime);

        Page<SysFileInfo> page = fileInfoMapper.selectPage(
            new Page<>(request.getPageNum(), request.getPageSize()), wrapper);
        List<FileInfoVO> list = page.getRecords().stream().map(fileInfoConverter::toVO).toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    @Transactional
    public FileInfoVO upload(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 5001, "上传文件不能为空");
        }
        if (!ALLOWED_FOLDERS.contains(folder)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 5001, "不支持的文件分类");
        }
        StoredFile storedFile;
        try (InputStream inputStream = file.getInputStream()) {
            storedFile = storageService.upload(new UploadCommand(
                inputStream, folder, file.getOriginalFilename(), file.getContentType(), file.getSize()));
        } catch (IOException exception) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 5003, "读取上传文件失败");
        }

        SysFileInfo entity = new SysFileInfo();
        entity.setFileName(storedFile.originalFilename());
        entity.setBucketName(folder);
        entity.setObjectName(storedFile.objectKey());
        entity.setFileSize(storedFile.size());
        entity.setFileSuffix(extractSuffix(storedFile.originalFilename()));
        entity.setContentType(storedFile.contentType());
        fileInfoMapper.insert(entity);
        entity.setFileUrl("/api/system/files/" + entity.getFileInfoId() + "/content");
        fileInfoMapper.updateById(entity);

        return fileInfoConverter.toVO(entity);
    }

    @Override
    public FileDownload downloadFile(Long fileInfoId) {
        SysFileInfo entity = fileInfoMapper.selectById(fileInfoId);
        if (entity == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 5002, "文件记录不存在");
        }
        Resource resource = storageService.download(entity.getObjectName());
        if (resource == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 5002, "文件内容不存在");
        }
        return new FileDownload(resource, entity.getFileName(), entity.getContentType(), entity.getFileSize());
    }

    @Override
    @Transactional
    public void deleteFile(Long fileInfoId) {
        SysFileInfo entity = fileInfoMapper.selectById(fileInfoId);
        if (entity == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 5002, "文件记录不存在");
        }
        storageService.delete(entity.getObjectName());
        this.removeById(fileInfoId);
    }

    @Override
    @Transactional
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        for (Long id : ids) {
            deleteFile(id);
        }
    }

    private String extractSuffix(String fileName) {
        if (fileName != null && fileName.contains(".")) {
            return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
        }
        return "";
    }
}
