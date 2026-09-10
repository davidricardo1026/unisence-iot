package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_sys_oper_log")
public class SysOperLog extends BaseAuditEntity {
    @TableId(type = IdType.AUTO)
    private Long operLogId;
    private String title;
    private Integer businessType;
    private String method;
    private String requestMethod;
    private String operatorCode;
    private String operIp;
    private String operUrl;
    private String operParam;
    private String jsonResult;
    private Integer status;
    private String errorMsg;
}
