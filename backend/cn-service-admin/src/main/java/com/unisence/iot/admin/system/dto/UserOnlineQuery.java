package com.unisence.iot.admin.system.dto;

import lombok.Data;

/**
 * 在线用户查询条件
 */
@Data
public class UserOnlineQuery {
    /**
     * 用户编码
     */
    private String userCode;

    /**
     * 登录地址
     */
    private String ipaddr;
}
