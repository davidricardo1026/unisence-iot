package com.unisence.iot.admin.rule.vo;

import com.unisence.iot.rule.config.EmitMode;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 规则列表项。
 *
 * <p><b>不含脚本正文</b>：过滤脚本与各档位的输出脚本各可达数千字符，
 * 列表页一次返回 20 条就是几十 KB 的无用载荷。脚本只在详情接口返回。
 */
@Data
public class RuleVO {

    /**
     * {@code INSTANT} / {@code WINDOW}。
     *
     * <p><b>必须回吐给前端</b>：两张规则表各有独立的 {@code AUTO_INCREMENT}，
     * 单凭 {@code ruleId} 无法定位一条规则，后续的详情、编辑、启停请求都要靠它选路径。
     */
    private String ruleKind;
    private Long ruleId;
    private String ruleCode;
    private String ruleName;
    private String messageType;
    private String errorPolicy;
    private Integer status;
    private Long revision;
    /**
     * 档位数。列表页据此一眼看出「这条规则分了几档」。
     */
    private Integer levelCount;
    /**
     * 绑定产品数。
     */
    private Integer productCount;
    /**
     * 即时规则的输出节奏；窗口规则为 {@code null}。
     */
    private EmitMode emitMode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer version;
}
