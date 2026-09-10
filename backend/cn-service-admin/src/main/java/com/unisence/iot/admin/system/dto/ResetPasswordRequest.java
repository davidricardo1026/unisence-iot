package com.unisence.iot.admin.system.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotBlank(message = "密码不能为空")
    @Pattern(regexp = "[0-9a-f]{64}", message = "密码必须是64位小写十六进制 SHA-256 散列串")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
}
