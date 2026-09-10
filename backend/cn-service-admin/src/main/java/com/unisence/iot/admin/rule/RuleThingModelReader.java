package com.unisence.iot.admin.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unisence.iot.admin.device.support.JsonMaps;
import com.unisence.iot.admin.entity.IotTmEvent;
import com.unisence.iot.admin.entity.IotTmProperty;
import com.unisence.iot.admin.mapper.IotTmEventMapper;
import com.unisence.iot.admin.mapper.IotTmPropertyMapper;
import com.unisence.iot.rule.sdk.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 admin 侧的物模型表读成 {@link ThingModelSnapshot}，供 {@code RuleConfigValidator} 校验
 * 规则引用的 identifier（错误码 5048 / 5049）。
 *
 * <p>与 engine 的 {@code ThingModelMetadataLoader} 是<b>同一份数据的两个读取方</b>：
 * engine 为热路径读、走一致性快照；这里为保存期校验读、走普通事务。
 * 两者都产出 SDK 的 {@code ThingModelSnapshot}，因此「admin 校验通过的规则，engine 一定也能校验通过」
 * —— 这正是把校验前置到保存期的意义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleThingModelReader {

    private final IotTmPropertyMapper propertyMapper;
    private final IotTmEventMapper eventMapper;
    private final JsonMaps jsonMaps;

    /**
     * 单个产品的物模型快照；无定义时返回空快照而不是 null，让校验器统一处理。
     */
    public ThingModelSnapshot read(Long productId) {
        Map<String, PropertyDefinition> properties = new HashMap<>();
        for (IotTmProperty row : propertyMapper.selectList(
            new LambdaQueryWrapper<IotTmProperty>().eq(IotTmProperty::getProductId, productId))) {
            PropertyDataType dataType;
            try {
                dataType = PropertyDataType.fromCode(row.getDataType());
            } catch (IllegalArgumentException e) {
                // 单条脏定义只跳过它自己：让一条越界的 data_type 使整个规则校验失败，
                // 会把「某个产品配错」放大成「所有规则都保存不了」
                log.error("物模型 data_type 取值域外，规则校验时已跳过该属性: productId={} identifier={} dataType={}",
                          productId, row.getIdentifier(), row.getDataType(), e);
                continue;
            }
            properties.put(row.getIdentifier(), new PropertyDefinition(
                row.getIdentifier(),
                row.getPropertyName(),
                dataType,
                row.getAccessMode(),
                row.getUnit(),
                row.getRetentionDays()));
        }

        Map<String, EventDefinition> events = new HashMap<>();
        for (IotTmEvent row : eventMapper.selectList(
            new LambdaQueryWrapper<IotTmEvent>().eq(IotTmEvent::getProductId, productId))) {
            try {
                events.put(row.getIdentifier().toLowerCase(java.util.Locale.ROOT), new EventDefinition(
                    row.getIdentifier(),
                    row.getEventName(),
                    EventLevel.fromCode(row.getEventType()),
                    parseParams(row.getInputParams()),
                    Boolean.TRUE.equals(row.getTtlEnabled()),
                    row.getTtlValue(),
                    row.getTtlUnit()));
            } catch (IllegalArgumentException e) {
                log.error("事件定义取值域外，规则校验时已跳过: productId={} identifier={}",
                          productId, row.getIdentifier(), e);
            }
        }
        return new ThingModelSnapshot(properties, events);
    }

    private List<EventParamDefinition> parseParams(String json) {
        List<Map<String, Object>> items = jsonMaps.readMapList(json);
        List<EventParamDefinition> params = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            params.add(new EventParamDefinition(
                str(item.get("identifier")),
                str(item.get("name")),
                PropertyDataType.fromCode(str(item.get("dataType")))));
        }
        return params;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
