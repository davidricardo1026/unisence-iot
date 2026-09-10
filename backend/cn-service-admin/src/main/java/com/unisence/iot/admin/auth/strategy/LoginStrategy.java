package com.unisence.iot.admin.auth.strategy;

import java.util.Map;

public interface LoginStrategy {
    /**
     * 获取登录凭证类型，如 "local", "sms", "oauth2_github"
     */
    String getIdentityType();

    /**
     * 认证逻辑，返回系统统一的 userId
     *
     * @param params 登录参数，如用户名和密码哈希等
     * @return 认证通过的用户ID
     */
    Long authenticate(Map<String, String> params);
}
