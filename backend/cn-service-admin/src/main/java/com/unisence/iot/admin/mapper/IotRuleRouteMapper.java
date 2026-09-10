package com.unisence.iot.admin.mapper;

import com.unisence.iot.admin.entity.IotRuleRoute;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;

public interface IotRuleRouteMapper extends AppBaseMapper<IotRuleRoute> {

    /**
     * 跨规则唯一性：在其它非删除透传规则中查找同 {@code messageType} 且产品与输出都相交的一条，返回其 {@code rule_code}；不存在返回 {@code null}。
     *
     * @param selfRuleId 编辑时排除自身；新建传 {@code null}
     */
    @Select("""
        <script>
        SELECT r.rule_code
          FROM us_iot_rule_route r
          JOIN us_iot_rule_route_product p ON p.rule_id = r.rule_id
          JOIN us_iot_rule_route_kafka_output o ON o.rule_id = r.rule_id
         WHERE r.deleted = 0
           AND r.message_type = #{messageType}
           AND (#{selfRuleId} IS NULL OR r.rule_id &lt;&gt; #{selfRuleId})
           AND p.product_id IN
           <foreach collection="productIds" item="pid" open="(" separator="," close=")">
             #{pid}
           </foreach>
           AND o.output_id IN
           <foreach collection="outputIds" item="oid" open="(" separator="," close=")">
             #{oid}
           </foreach>
         LIMIT 1
        </script>
        """)
    String findConflictingRuleCode(@Param("selfRuleId") Long selfRuleId,
                                   @Param("messageType") String messageType,
                                   @Param("productIds") Collection<Long> productIds,
                                   @Param("outputIds") Collection<Long> outputIds);
}
