package com.unisence.iot.rule.config;

import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.sdk.ErrorPolicy;

import java.util.List;

/**
 * 一条规则的完整定义视图（脚本正文除外）。
 *
 * <h2>为什么是密封接口而不是带判别字段的单一 record</h2>
 * 两类规则的<b>成本结构完全不同</b>：窗口规则每 (规则, 设备, 窗口) 占一份累加器，
 * 直接决定 worker 状态内存体积、state Topic 吞吐与 restore 时间；即时规则零状态。
 * 用一个 record 加 {@code kind} 字段表达时，窗口与聚合两列在即时规则上恒为 null，
 * 而运行期是否碰窗口状态要靠读那个字段来决定 —— 漏一个分支就是静默的行为错误。
 *
 * <p>密封接口把这件事交给编译器：{@code switch} 必须覆盖两个分支，
 * <b>将来新增第三类规则时所有分派点编译期报错</b>。2026-08-02 那批「声明了、校验不拦、
 * 运行期静默无效」的缺陷（{@code WindowType.COUNT}、窗口规则的 {@code ON_RECOVERY}）
 * 全部出自「用取值表达类别」这一模式。
 *
 * <h2>档位</h2>
 * 规则决定<b>算什么</b>（监听哪个信号、怎么计算），档位决定<b>什么时候报</b>。
 * 同一 (规则, 设备) 在任一时刻恰好处于一个档位（含隐含的 NORMAL），
 * 输出只在档位发生变化时产生 —— 见 {@link LevelDefinition}。
 */
public sealed interface RuleDefinition permits InstantRuleDefinition, WindowRuleDefinition {

    long ruleId();

    String ruleCode();

    MessageType messageType();

    ListenerConfig listener();

    /**
     * 按 {@code severity} 升序（越严重越靠前），构建期排好。
     *
     * <p>运行期判档就是「顺序扫，取第一个满足条件的」，因此顺序是语义的一部分，
     * 不是展示偏好。放到执行期排会让每条消息重排一次，且不同实例的排序稳定性无法保证。
     */
    List<LevelDefinition> levels();

    ErrorPolicy errorPolicy();

    long revision();

    /**
     * 类别标签，用于穿过数据库、运行时状态 key 与 Kafka header 这些没有类型的边界。
     *
     * <p>提供它不是为了替代模式匹配 —— 需要按类别<b>分派行为</b>的地方一律用
     * {@code switch}，让编译器兜底新增类别时的遗漏。
     */
    default RuleKind kind() {
        return this instanceof WindowRuleDefinition ? RuleKind.WINDOW : RuleKind.INSTANT;
    }

    /**
     * 全局唯一的规则键：两张规则表各有独立的 {@code AUTO_INCREMENT}，单靠 {@code ruleId} 会撞。
     */
    default String ruleKey() {
        return kind().ruleKey(ruleId());
    }
}
