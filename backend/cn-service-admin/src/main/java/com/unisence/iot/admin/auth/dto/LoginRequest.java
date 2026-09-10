package com.unisence.iot.admin.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public class LoginRequest {

    @NotBlank(message = "登录通道类型不能为空")
    private String identityType;

    @NotNull(message = "登录参数不能为空")
    private Map<String, String> authParams;

    public String getIdentityType() {
        return identityType;
    }

    public void setIdentityType(String identityType) {
        this.identityType = identityType;
    }

    public Map<String, String> getAuthParams() {
        return authParams;
    }

    public void setAuthParams(Map<String, String> authParams) {
        this.authParams = authParams;
    }
}
