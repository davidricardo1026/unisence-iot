package com.unisence.iot.admin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.unisence.iot.common.entity.BaseAuditEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * {@code us_iot_rule_level} —— 规则档位，两类规则共用，用 {@code rule_kind} 区分归属。
 *
 * <p><b>不做逻辑删除</b>：档位随规则整体保存（一个保存聚合），删档位就是从该规则的
 * 档位集合里移除，没有独立生命周期。因此继承 {@link BaseAuditEntity} 而非
 * {@code BaseEntity} —— 没有 update_by / deleted / version。
 *
 * <p>保存时整体替换（先按 {@code (rule_kind, rule_id)} 删净再插入），
 * 这也是 {@code level_id} 会在每次保存后变化的原因；运行期的档位身份由
 * {@code severity} 与 {@code level_code} 承担，两者在规则内唯一。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("us_iot_rule_level")
public class IotRuleLevel extends BaseAuditEntity {

    @TableId(type = IdType.AUTO)
    private Long levelId;

    /**
     * {@code INSTANT} / {@code WINDOW}，即 {@code RuleKind.name()}。
     */
    private String ruleKind;
    private Long ruleId;

    /**
     * 档位编码；作为输出 header 交给下游做分派。规则内唯一。
     */
    private String levelCode;
    /**
     * 越小越严重；规则内唯一，同时也是判档顺序。
     */
    private Integer severity;
    /**
     * {@code THRESHOLD} / {@code SCRIPT}；SCRIPT 仅即时规则可用。
     */
    private String conditionKind;
    /**
     * JSON：{@code {operator, threshold}}；{@code conditionKind=THRESHOLD} 时必填。
     */
    private String thresholdConfig;
    /**
     * {@code conditionKind=SCRIPT} 时必填：返回 Boolean 的 Groovy 裸脚本。
     */
    private String conditionScript;
    /**
     * 档位跃迁时执行的输出脚本，NOT NULL。
     *
     * <p>每档各一份：「危急」与「预警」的文案、字段、下游动作本就不同，
     * 共用一份只会逼作者在脚本里按 severity 写 if-else。
     */
    private String outputScript;
    /**
     * 可选的额外节流；档位跃迁本身已抑制持续满足的重复输出，通常不需要。
     */
    private Long cooldownMillis;
}
