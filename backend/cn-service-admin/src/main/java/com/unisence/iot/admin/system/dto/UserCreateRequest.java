package com.unisence.iot.admin.system.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.unisence.iot.admin.aop.sensitive.Sensitive;
import com.unisence.iot.admin.aop.sensitive.SensitiveStrategy;
import com.unisence.iot.common.validation.NoInvisibleUnicode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class UserCreateRequest {
    @NotBlank(message = "用户编码不能为空")
    @Size(max = 50, message = "用户编码不能超过50字符")
    @NoInvisibleUnicode(message = "用户编码不能包含不可见 Unicode 字符")
    private String userCode;

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

    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "[0-9a-f]{64}", message = "密码必须是64位小写十六进制 SHA-256 散列串")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private Integer status = 1;
    private List<Long> roleIds;
}
