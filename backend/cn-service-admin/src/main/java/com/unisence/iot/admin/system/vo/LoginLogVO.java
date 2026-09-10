package com.unisence.iot.admin.system.vo;

import io.github.easytrans.core.annotation.Translatable;
import io.github.easytrans.core.annotation.TranslateField;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Translatable
public class LoginLogVO {
    private Long loginLogId;
    private String userCode;
    @TranslateField(source = "userCode", type = "USER_CODE_SERVICE")
    private String userName;
    private String ipaddr;
    private String loginLocation;
    private String browser;
    private String os;
    private Integer status;
    private String msg;
    private LocalDateTime loginTime;
}
