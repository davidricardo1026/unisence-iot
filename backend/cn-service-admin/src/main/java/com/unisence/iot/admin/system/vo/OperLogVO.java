package com.unisence.iot.admin.system.vo;

import io.github.easytrans.core.annotation.Translatable;
import io.github.easytrans.core.annotation.TranslateField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Translatable
public class OperLogVO {
    private Long operLogId;
    private String title;
    private Integer businessType;
    private String method;
    private String requestMethod;
    private String operatorCode;
    @TranslateField(source = "operatorCode", type = "USER_CODE_SERVICE")
    private String userName;
    private String operIp;
    private String operUrl;
    private String operParam;
    private String jsonResult;
    private Integer status;
    private String errorMsg;
    private LocalDateTime createTime;
}
