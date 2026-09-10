package com.unisence.iot.admin.rule.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

/**
 * 规则详情：在列表项之上补齐配置对象、脚本正文、档位与绑定产品。
 *
 * <p>{@code valueConfig} 只在即时规则上有值，{@code windowConfig} / {@code aggregateConfig}
 * 只在窗口规则上有值 —— 这是<b>响应</b>而非请求，返回一个统一形状让前端少写一个分支；
 * 而写入侧仍然是两个互不相交的请求类型，配错在结构上不可能发生。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class RuleDetailVO extends RuleVO {

    private Map<String, Object> listenerConfig;
    /**
     * 即时规则专有。
     */
    private Map<String, Object> valueConfig;
    /**
     * 窗口规则专有。
     */
    private Map<String, Object> windowConfig;
    /**
     * 窗口规则专有。
     */
    private Map<String, Object> aggregateConfig;

    private String filterScript;
    private String scriptSha256;
    private Map<String, Object> compileResult;
    /**
     * 按 severity 升序 —— 与运行期判档顺序一致。
     */
    private List<RuleLevelVO> levels;
    private List<RuleProductVO> products;
}
