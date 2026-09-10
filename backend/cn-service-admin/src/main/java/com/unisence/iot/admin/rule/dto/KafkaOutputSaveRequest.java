package com.unisence.iot.admin.rule.dto;

import com.unisence.iot.rule.config.KafkaOutputPurpose;
import com.unisence.iot.rule.config.OutputFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Kafka 输出定义新增/编辑请求。
 *
 * <p>五个字段均为<b>配置定义必填</b>。已被引用的定义只接受 {@code outputName} 变化，其它字段变化由 Service 拒绝。
 */
@Data
public class KafkaOutputSaveRequest {

    @NotBlank(message = "输出编码不能为空")
    @Size(max = 50, message = "输出编码最长 50 字符")
    private String outputCode;

    @NotBlank(message = "输出名称不能为空")
    @Size(max = 120, message = "输出名称最长 120 字符")
    private String outputName;

    @NotNull(message = "用途不能为空")
    private KafkaOutputPurpose purpose;

    /**
     * Kafka 合法 Topic 名：{@code [a-zA-Z0-9._-]}，1～249 字符；{@code .} 与 {@code ..} 由 Service 另行拒绝。
     */
    @NotBlank(message = "目标 Topic 不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9._-]{1,249}$", message = "Topic 只允许字母、数字、点、下划线、连字符，最长 249 字符")
    private String targetTopic;

    @NotNull(message = "编码格式不能为空")
    private OutputFormat format;

    /**
     * 编辑时必填的乐观锁版本；新增时忽略。
     */
    private Integer version;
}
