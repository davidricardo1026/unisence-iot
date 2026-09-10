package com.unisence.iot.admin.system.dto;

import com.unisence.iot.admin.aop.sensitive.Sensitive;
import com.unisence.iot.admin.aop.sensitive.SensitiveStrategy;
import com.unisence.iot.common.validation.NoInvisibleUnicode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UserUpdateRequest {
    @NotBlank(message = "用户名称不能为空")
    @Size(max = 50, message = "用户名称不能超过50字符")
    @NoInvisibleUnicode(message = "用户名称不能包含不可见 Unicode 字符")
    private String userName;

    @Sensitive(strategy = SensitiveStrategy.PHONE)
    @Size(max = 20, message = "手机号不能超过20字符")
    @NoInvisibleUnicode(message = "手机号不能包含不可见 Unicode 字符")
    private String phone;

    @NotNull(message = "部门不能为空")
    private Long deptId;

    @NotNull(message = "状态不能为空")
    private Integer status;

    private List<Long> roleIds;

    @NotNull(message = "版本号不能为空")
    private Integer version;
}
