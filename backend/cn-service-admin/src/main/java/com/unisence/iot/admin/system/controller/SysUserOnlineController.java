package com.unisence.iot.admin.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.system.dto.UserOnlineQuery;
import com.unisence.iot.admin.system.service.SysUserOnlineService;
import com.unisence.iot.admin.system.vo.SysUserOnlineVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 在线用户监控
 */
@RestController
@RequestMapping("/system/online")
@RequiredArgsConstructor
public class SysUserOnlineController {

    private final SysUserOnlineService onlineService;

    /**
     * 查询在线用户列表
     */
    @GetMapping("/list")
    @SaCheckPermission("sys:online:list")
    public PageResult<SysUserOnlineVO> list(PageRequest<UserOnlineQuery> request) {
        return onlineService.listOnlineUsers(request);
    }

    /**
     * 强退用户
     */
    @DeleteMapping("/{tokenId}")
    @SaCheckPermission("sys:online:kickout")
    @OperLog(title = "在线用户", businessType = BusinessType.FORCE_KICKOUT)
    public void kickout(@PathVariable String tokenId) {
        onlineService.kickout(tokenId);
    }
}
