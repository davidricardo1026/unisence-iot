package com.unisence.iot.admin.auth.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unisence.iot.admin.entity.SysAuthIdentity;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.mapper.SysAuthIdentityMapper;
import com.unisence.iot.admin.mapper.SysUserMapper;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.validation.InvisibleUnicode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class LocalLoginStrategy implements LoginStrategy {

    private final SysUserMapper userMapper;
    private final SysAuthIdentityMapper authIdentityMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public String getIdentityType() {
        return "local";
    }

    @Override
    public Long authenticate(Map<String, String> params) {
        String username = params.get("userCode");
        String password = params.get("password"); // 接收前端经 SHA-256 计算后的 64 位离散串

        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 1001, "用户名或密码不能为空");
        }
        if (InvisibleUnicode.contains(username) || !password.matches("[0-9a-f]{64}")) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, 1002, "用户名或密码错误");
        }

        // 1. 查找用户（防枚举：不存在与密码错误返回统一文案）
        SysUser user = userMapper.selectOne(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getUserCode, username)
        );
        if (user == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, 1002, "用户名或密码错误");
        }

        // 2. 检查用户状态是否禁用
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(HttpStatus.FORBIDDEN, 1003, "您的账号已被禁用");
        }

        // 3. 校验凭证
        SysAuthIdentity identity = authIdentityMapper.selectOne(
            new LambdaQueryWrapper<SysAuthIdentity>()
                .eq(SysAuthIdentity::getUserId, user.getUserId())
                .eq(SysAuthIdentity::getIdentityType, "local")
                .eq(SysAuthIdentity::getIdentifier, username)
        );

        if (identity == null || identity.getCredential() == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, 1002, "用户名或密码错误");
        }

        // 4. 调用 matches 安全对齐（防枚举：与“用户不存在”分支文案一致）
        boolean matches = passwordEncoder.matches(password, identity.getCredential());
        if (!matches) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, 1002, "用户名或密码错误");
        }

        return user.getUserId();
    }
}
