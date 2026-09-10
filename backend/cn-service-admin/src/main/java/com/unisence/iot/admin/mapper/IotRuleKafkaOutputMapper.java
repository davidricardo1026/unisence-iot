package com.unisence.iot.admin.mapper;

import com.unisence.iot.admin.entity.IotRuleKafkaOutput;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface IotRuleKafkaOutputMapper extends AppBaseMapper<IotRuleKafkaOutput> {

    /**
     * 引用计数 = 档位绑定数 + 透传路由绑定数。非零时禁止删除、禁止改路。
     */
    @Select("""
        SELECT (SELECT COUNT(*) FROM us_iot_rule_level_kafka_output WHERE output_id = #{outputId})
             + (SELECT COUNT(*) FROM us_iot_rule_route_kafka_output WHERE output_id = #{outputId})
        """)
    int countReferences(@Param("outputId") Long outputId);

    /**
     * 引用本输出定义的即时 / 窗口 / 透传规则编码，供删除拒绝文案列出。
     */
    @Select("""
        SELECT i.rule_code
          FROM us_iot_rule_level_kafka_output b
          JOIN us_iot_rule_level l ON l.level_id = b.level_id AND l.rule_kind = 'INSTANT'
          JOIN us_iot_rule_instant i ON i.rule_id = l.rule_id AND i.deleted = 0
         WHERE b.output_id = #{outputId}
        UNION
        SELECT w.rule_code
          FROM us_iot_rule_level_kafka_output b
          JOIN us_iot_rule_level l ON l.level_id = b.level_id AND l.rule_kind = 'WINDOW'
          JOIN us_iot_rule_window w ON w.rule_id = l.rule_id AND w.deleted = 0
         WHERE b.output_id = #{outputId}
        UNION
        SELECT r.rule_code
          FROM us_iot_rule_route_kafka_output b
          JOIN us_iot_rule_route r ON r.rule_id = b.rule_id AND r.deleted = 0
         WHERE b.output_id = #{outputId}
        """)
    List<String> listReferencingRuleCodes(@Param("outputId") Long outputId);
}
