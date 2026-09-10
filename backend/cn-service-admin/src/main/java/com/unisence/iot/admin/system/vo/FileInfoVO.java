package com.unisence.iot.admin.system.vo;

import io.github.easytrans.core.annotation.Translatable;
import io.github.easytrans.core.annotation.TranslateField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Translatable
public class FileInfoVO {
    private Long fileInfoId;
    private String fileName;
    private String bucketName;
    private String objectName;
    private Long fileSize;
    private String fileSuffix;
    private String fileUrl;

    private Long createBy;

    @TranslateField(source = "createBy", type = "USER_SERVICE")
    private String createByName;

    private LocalDateTime createTime;
}
