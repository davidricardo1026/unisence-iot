package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.unisence.iot.admin.entity.SysAuthIdentity;

public interface SysAuthIdentityService extends IService<SysAuthIdentity> {
    /**
     * 根据用户ID删除其所有认证凭证（逻辑删除）
     */
    void deleteByUserId(Long userId);
}
