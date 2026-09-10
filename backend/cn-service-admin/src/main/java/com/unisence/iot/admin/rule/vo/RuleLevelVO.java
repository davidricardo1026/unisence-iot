package com.unisence.iot.admin.rule.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 档位详情项。按 {@code severity} 升序返回 —— 那既是严重度顺序，也是运行期的判档顺序，
 * 界面按同一顺序展示才不会让人误解「哪一档先判」。
 */
@Data
public class RuleLevelVO {

    private Long levelId;
    private String levelCode;
    private Integer severity;
    private String conditionKind;
    private Map<String, Object> thresholdConfig;
    private String conditionScript;
    private String outputScript;
    private Long cooldownMillis;
    private List<KafkaOutputVO> kafkaOutputs;
}
