package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.storage.FileDownload;
import com.unisence.iot.admin.system.dto.FileQuery;
import com.unisence.iot.admin.system.vo.FileInfoVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface SysFileInfoService {

    PageResult<FileInfoVO> pageFiles(PageRequest<FileQuery> request);

    FileInfoVO upload(MultipartFile file, String folder);

    FileDownload downloadFile(Long fileInfoId);

    void deleteFile(Long fileInfoId);

    void batchDelete(List<Long> ids);
}
