package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.UserOnlineQuery;
import com.unisence.iot.admin.system.vo.SysUserOnlineVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

/**
 * 在线用户 Service 接口
 *
 * <p>在线用户数据完全由 Sa-Token 托管在 Redis 中（无数据库持久表）。
 * 登录时由 AuthApplicationServiceImpl 将下列键写入 TokenSession，本服务读取展示。</p>
 */
public interface SysUserOnlineService {

    /**
     * TokenSession 键：登录账号
     */
    String SESSION_KEY_USER_CODE = "userCode";
    /**
     * TokenSession 键：登录 IP
     */
    String SESSION_KEY_IP = "ipaddr";
    /**
     * TokenSession 键：浏览器标识
     */
    String SESSION_KEY_BROWSER = "browser";
    /**
     * TokenSession 键：操作系统
     */
    String SESSION_KEY_OS = "os";
    /**
     * TokenSession 键：登录地点
     */
    String SESSION_KEY_LOCATION = "loginLocation";
    /**
     * TokenSession 键：用户姓名
     */
    String SESSION_KEY_USER_NAME = "userName";

    /**
     * 分页查询在线用户（username 模糊匹配，ipaddr 精确匹配）
     */
    PageResult<SysUserOnlineVO> listOnlineUsers(PageRequest<UserOnlineQuery> request);

    /**
     * 按 token 强退用户
     *
     * @param tokenId 原始 token 值（uuid，非 Redis Key）
     */
    void kickout(String tokenId);
}
