package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_product")
public class IotProduct extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long productId;
    private String productKey;
    private String productName;
    private Integer nodeType;
    private Integer netType;
    private String vendor;
    private String model;
    private String icon;
    private String iconUrl;
    private String description;
    /**
     * JSON 字符串
     */
    private String attributes;
    /**
     * JSON 字符串
     */
    private String deviceFormSchema;
    /**
     * 已发布的设备表单 schema/index 代际（metadata-sync-bus.md §6.4）。
     *
     * <p>engine 的设备缓存条目携带该值，读取时与当前产品比对：不匹配即视为 miss。
     * 这让「产品表单换代」只需推进一个整数，而不必扫描或广播该产品下的百万条设备缓存。
     *
     * <p>因此只有 schema 真正变化并完成索引重建时才允许 +1；产品改名、TTL 调整等
     * 不影响设备投影的变更绝不能推进它。
     */
    private Integer deviceFormVersion;
    private Integer productType;
}
