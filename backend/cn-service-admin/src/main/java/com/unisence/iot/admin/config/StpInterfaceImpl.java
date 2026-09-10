package com.unisence.iot.admin.config;

import cn.dev33.satoken.stp.StpInterface;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.system.service.SysUserCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserCacheService userCacheService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 1. 获取当前登录用户主体
        Long userId = Long.parseLong(loginId.toString());
        SysUser user = userCacheService.getById(userId);

        // 2. 严格执行用户账号 (Username) 大小写敏感匹配：
        // 当且仅当用户登录账号完全等于小写 "superAdmin" 时，判定为至高超级管理员。
        // 直接向 Sa-Token 返回 ["*"] 全局通配符，Sa-Token 底层会对 "*" 进行一票放行。
        // 这实现了纯程序写死的特权短路，不查询任何底层资源权限表。
        if (user != null && "superAdmin".equals(user.getUserCode())) {
            return Collections.singletonList("*");
        }

        // 3. 常规普通用户，反查缓存的明细权限点
        return userCacheService.getPermsByUserId(userId);
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // 4. 对角色列表，Sa-Token 框架本身对字符串 "superAdmin" 没有任何内置的特殊处理（它不把 "superAdmin" 当成特殊通配符）。
        // 既然超级管理员身份已经由用户的编码（userCode = "superAdmin"）在权限注入阶段通过 "*" 完美代理并写死放行，
        // 那么 getRoleList 这里就不需要任何多余的硬编码或虚假角色注入，直接返还其在数据库中实际绑定的角色集合即可。
        Long userId = Long.parseLong(loginId.toString());
        return userCacheService.getRoleCodesByUserId(userId);
    }
}
