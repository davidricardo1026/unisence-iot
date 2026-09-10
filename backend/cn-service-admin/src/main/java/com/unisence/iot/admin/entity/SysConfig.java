package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_sys_config")
public class SysConfig extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long configId;
    private String configName;
    private String configKey;
    private String configValue;
    private Integer configType;
}
