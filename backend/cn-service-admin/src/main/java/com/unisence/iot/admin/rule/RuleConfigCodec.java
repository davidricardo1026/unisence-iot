package com.unisence.iot.admin.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisence.iot.admin.rule.dto.InstantRuleSaveRequest;
import com.unisence.iot.admin.rule.dto.RuleLevelRequest;
import com.unisence.iot.admin.rule.dto.RuleSaveRequest;
import com.unisence.iot.admin.rule.dto.WindowRuleSaveRequest;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.config.*;
import com.unisence.iot.rule.sdk.ErrorPolicy;
import com.unisence.iot.rule.sdk.ThresholdOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 请求 Map ↔ SDK 配置对象 ↔ 数据库 JSON 列 的转换。
 *
 * <p><b>落库的是「配置对象序列化后的 JSON」，不是请求里的原始 Map</b>。这一点是与 engine
 * 对齐的关键：engine 的 {@code RuleMetadataLoader} 按 SDK record 的组件名读这几列
 * （{@code type} / {@code sizeMillis} / {@code valueIdentifier} …）。
 * 若直接把请求 Map 原样存库，客户端多传、少传或拼错一个键都会原样落进去，
 * 而 engine 读到时只会静默取到 null —— 表现为「保存成功但规则行为不对」。
 * 先反序列化成 record（拼错即报错）、再序列化回 JSON，字段名由构造保证一致。
 *
 * <p>未知字段<b>拒绝</b>而不是忽略：配置键拼错几乎总是笔误，静默忽略会让用户以为设置生效了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleConfigCodec {

    private final ObjectMapper objectMapper;

    /**
     * {@code threshold_config} 的落库形状。
     *
     * <p>单开一个 record 而不是把 operator/threshold 平铺成两列：
     * 它们只在 {@code conditionKind=THRESHOLD} 时有意义，平铺会让另一半行留两个 NULL 列，
     * 与 {@code ddl-conventions.md}「不留无依据字段」的取向相悖。
     * JSON 列整列为 NULL 是「这一类条件没有配置对象」的自然表达。
     */
    public record ThresholdSpec(ThresholdOperator operator, Double threshold) {
    }

    /**
     * 请求 → SDK 规则定义，供 {@code RuleConfigValidator} 校验。
     *
     * <p>返回的是密封接口的某个具体实现，由请求的运行时类型决定 ——
     * 而请求类型由接口路径决定，因此不存在「客户端声明的类别与实际配置不符」这种状态。
     *
     * @param revision 仅用于构造完整的定义对象；真实值由保存事务推进
     */
    public RuleDefinition toDefinition(long ruleId, String ruleCode, RuleSaveRequest request, long revision) {
        MessageType messageType = parseMessageType(request.getMessageType());
        ListenerConfig listener = convert(request.getListenerConfig(), ListenerConfig.class,
                                          "listenerConfig", RuleConfigErrorCode.LISTENER_CONFIG_INVALID);
        List<LevelDefinition> levels = toLevels(request.getLevels());
        ErrorPolicy errorPolicy = request.getErrorPolicy() == null
            ? ErrorPolicy.DLQ_MESSAGE
            : parseEnum(ErrorPolicy.class, request.getErrorPolicy(), "errorPolicy",
                        RuleConfigErrorCode.LEVEL_CONFIG_INVALID);

        if (request instanceof InstantRuleSaveRequest instant) {
            return new InstantRuleDefinition(
                ruleId, ruleCode, messageType, listener,
                convert(instant.getValueConfig(), ValueConfig.class,
                        "valueConfig", RuleConfigErrorCode.VALUE_CONFIG_INVALID),
                instant.getEmitMode(),
                levels, errorPolicy, revision);
        }
        if (request instanceof WindowRuleSaveRequest window) {
            return new WindowRuleDefinition(
                ruleId, ruleCode, messageType, listener,
                convert(window.getWindowConfig(), WindowConfig.class,
                        "windowConfig", RuleConfigErrorCode.WINDOW_CONFIG_INVALID),
                convert(window.getAggregateConfig(), AggregateConfig.class,
                        "aggregateConfig", RuleConfigErrorCode.AGGREGATE_CONFIG_INVALID),
                levels, errorPolicy, revision);
        }
        throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 1001,
                                    "未知的规则保存请求类型: " + request.getClass().getSimpleName());
    }

    /**
     * 档位请求 → SDK 档位定义。
     *
     * <p>{@code levelId} 一律填 0：档位随规则整体替换保存，真实 id 由 {@code AUTO_INCREMENT}
     * 在插入时产生。校验阶段不需要它 —— 校验关心的是 severity 唯一性与条件必填，都与 id 无关。
     */
    private List<LevelDefinition> toLevels(List<RuleLevelRequest> requests) {
        if (requests == null) {
            return List.of();
        }
        List<LevelDefinition> levels = new ArrayList<>(requests.size());
        for (RuleLevelRequest request : requests) {
            ConditionKind kind = parseEnum(ConditionKind.class, request.getConditionKind(),
                                           "levels.conditionKind", RuleConfigErrorCode.LEVEL_CONFIG_INVALID);
            ThresholdSpec threshold = convert(request.getThresholdConfig(), ThresholdSpec.class,
                                              "levels.thresholdConfig", RuleConfigErrorCode.LEVEL_CONFIG_INVALID);
            levels.add(new LevelDefinition(
                0L,
                request.getLevelCode(),
                request.getSeverity() == null ? 0 : request.getSeverity(),
                kind,
                threshold == null ? null : threshold.operator(),
                threshold == null ? null : threshold.threshold(),
                request.getCooldownMillis()));
        }
        return levels;
    }

    /**
     * 档位定义 → {@code threshold_config} 落库 JSON；非阈值档位返回 null。
     */
    public String thresholdJson(LevelDefinition level) {
        if (level.conditionKind() != ConditionKind.THRESHOLD) {
            return null;
        }
        return toJson(new ThresholdSpec(level.operator(), level.threshold()));
    }

    /**
     * 配置对象 → 落库 JSON。{@code null} 直接返回 {@code null}，让可空列保持 NULL 而不是字符串 "null"。
     */
    public String toJson(Object config) {
        if (config == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception e) {
            log.error("规则配置序列化失败: type={}", config.getClass().getSimpleName(), e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 1001, "规则配置序列化失败");
        }
    }

    /**
     * 落库 JSON → 展示用 Map，供详情接口回吐。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("规则配置反序列化失败: json={}", json, e);
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 1001, "规则配置反序列化失败");
        }
    }

    private <T> T convert(Map<String, Object> source, Class<T> type, String field, RuleConfigErrorCode code) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.convertValue(source, type);
        } catch (IllegalArgumentException e) {
            // 未知键、类型不符、枚举取值域外都落在这里；带上 SDK 的配置错误码便于前端定位到字段
            log.warn("规则配置字段非法: field={} raw={}", field, source, e);
            throw new BusinessException(HttpStatus.BAD_REQUEST, code.code(),
                                        field + " 配置非法: " + rootCause(e));
        }
    }

    private MessageType parseMessageType(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        RuleConfigErrorCode.LISTENER_CONFIG_INVALID.code(),
                                        "messageType 不能为空");
        }
        try {
            // MessageType 的落库值是小写 code（property / event…），与枚举名不同，单独处理
            return MessageType.fromCode(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        RuleConfigErrorCode.LISTENER_CONFIG_INVALID.code(),
                                        "messageType 取值非法: " + value);
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field, RuleConfigErrorCode code) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, code.code(), field + " 不能为空");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, code.code(), field + " 取值非法: " + value);
        }
    }

    private static String rootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
