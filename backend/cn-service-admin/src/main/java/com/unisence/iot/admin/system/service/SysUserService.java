package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.ResetPasswordRequest;
import com.unisence.iot.admin.system.dto.UserCreateRequest;
import com.unisence.iot.admin.system.dto.UserQuery;
import com.unisence.iot.admin.system.dto.UserUpdateRequest;
import com.unisence.iot.admin.system.vo.UserFormOptionsVO;
import com.unisence.iot.admin.system.vo.UserVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

public interface SysUserService {

    PageResult<UserVO> pageUsers(PageRequest<UserQuery> request);

    UserFormOptionsVO getFormOptions();

    Long createUser(UserCreateRequest request);

    void updateUser(Long userId, UserUpdateRequest request);

    void deleteUser(Long userId);

    void resetPassword(Long userId, ResetPasswordRequest request);

    /**
     * 获取当前登录用户的最新配置信息（含角色与最新权限）
     */
    UserVO getUserProfile();
}
