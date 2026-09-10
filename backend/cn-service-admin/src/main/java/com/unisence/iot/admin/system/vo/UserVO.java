package com.unisence.iot.admin.system.vo;

import com.unisence.iot.admin.aop.sensitive.Sensitive;
import com.unisence.iot.admin.aop.sensitive.SensitiveStrategy;
import io.github.easytrans.core.annotation.Translatable;
import io.github.easytrans.core.annotation.TranslateField;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Translatable
public class UserVO {
    private Long userId;
    private String userCode;
    private String userName;

    @Sensitive(strategy = SensitiveStrategy.PHONE)
    private String phone;
    private Integer status;
    private Long deptId;

    @TranslateField(source = "deptId", type = "DEPT_SERVICE")
    private String deptName;

    private Long createBy;

    @TranslateField(source = "createBy", type = "USER_SERVICE")
    private String createByName;

    private LocalDateTime createTime;
    private Integer version;
    private List<Long> roleIds;
    private List<String> roleNames;
    private List<String> roles;
    private List<String> perms;
    private List<MenuTreeVO> menus;
}
