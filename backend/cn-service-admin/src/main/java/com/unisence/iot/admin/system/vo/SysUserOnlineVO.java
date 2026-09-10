package com.unisence.iot.admin.system.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线用户 VO
 */
@Data
public class SysUserOnlineVO {
    /**
     * 会话编号
     */
    private String tokenId;

    /**
     * 用户账号
     */
    private String userCode;

    /**
     * 用户姓名
     */
    private String userName;

    /**
     * 登录IP地址
     */
    private String ipaddr;

    /**
     * 登录地点
     */
    private String loginLocation;

    /**
     * 浏览器类型
     */
    private String browser;

    /**
     * 操作系统
     */
    private String os;

    /**
     * 登录时间
     */
    private LocalDateTime loginTime;
}
