package com.unisence.iot.admin.rule.kafka;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.Set;

/**
 * {@code app.admin.rule-output.*} —— 管理端保存 Kafka 输出定义时的 Topic 治理配置。
 *
 * <p>{@code allowedTopicPrefixes} 与 {@code forbiddenTopics} 是跨服务共享键空间：与 engine 的 {@code app.engine.route.*}、
 * rule-stream 的 {@code app.rule-stream.output.kafka.*} 三处必须逐项一致，漂移时以数据面为准。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.admin.rule-output")
public class AdminRuleOutputProperties {

    @NotNull
    @Valid
    private Kafka kafka = new Kafka();

    @NotEmpty
    private List<String> allowedTopicPrefixes;

    @NotEmpty
    private Set<String> forbiddenTopics;

    @Data
    public static class Kafka {
        /**
         * 只用于 Kafka Admin 只读 metadata；管理端不持有 consumer / producer。
         */
        @NotBlank
        private String bootstrapServers;
        @Min(1)
        private int describeTimeoutMs = 5000;
    }
}
