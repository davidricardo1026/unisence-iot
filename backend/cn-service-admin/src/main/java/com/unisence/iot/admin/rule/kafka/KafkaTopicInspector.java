package com.unisence.iot.admin.rule.kafka;

import com.unisence.iot.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Kafka Topic 只读探测：保存输出定义时确认目标 Topic 已由运维预创建。
 *
 * <p>进程内单例，持有一个 {@code org.apache.kafka.clients.admin.Admin}，只调用 {@code describeTopics}；
 * 不创建、不写入、不消费。这是管理端唯一允许触碰 Kafka 的地方。
 */
@Slf4j
@Component
public class KafkaTopicInspector implements AutoCloseable {

    private static final Pattern TOPIC_NAME = Pattern.compile("^[a-zA-Z0-9._-]{1,249}$");

    private final AdminRuleOutputProperties props;
    private volatile Admin admin;

    /**
     * 只保存配置；{@code Admin} 客户端在首次 {@link #exists} 时惰性创建并复用，避免 Kafka 不可达时阻塞管理端启动。
     */
    public KafkaTopicInspector(AdminRuleOutputProperties props) {
        this.props = props;
    }

    /**
     * @return Topic 存在返回 {@code true}；{@code UnknownTopicOrPartitionException}（含被 {@code ExecutionException} 包裹）返回 {@code false}
     * @throws com.unisence.iot.common.exception.BusinessException 超时或其它元数据错误（HTTP 503，业务码 5061），
     *                                                             调用方不得据此把 Topic 当作不存在
     */
    public boolean exists(String topic) {
        try {
            admin().describeTopics(List.of(topic)).topicNameValues().get(topic)
                .get(props.getKafka().getDescribeTimeoutMs(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Kafka describeTopics 被中断: topic={}", topic, e);
            throw unavailable();
        } catch (UnknownTopicOrPartitionException e) {
            return false;
        } catch (ExecutionException | CompletionException e) {
            if (isUnknownTopic(e)) {
                return false;
            }
            log.error("Kafka 元数据不可用: topic={}", topic, e);
            throw unavailable();
        } catch (TimeoutException e) {
            log.error("Kafka describeTopics 超时: topic={}", topic, e);
            throw unavailable();
        } catch (Exception e) {
            if (isUnknownTopic(e)) {
                return false;
            }
            log.error("Kafka 元数据不可用: topic={}", topic, e);
            throw unavailable();
        }
    }

    /**
     * Topic 名称语法校验（不访问 Kafka）：{@code [a-zA-Z0-9._-]{1,249}} 且不是 {@code .} / {@code ..}。
     *
     * @return {@code null} 合法，否则返回原因
     */
    public static String syntaxViolation(String topic) {
        if (topic == null || topic.isBlank()) {
            return "Topic 不能为空";
        }
        if (".".equals(topic) || "..".equals(topic)) {
            return "Topic 不能为 . 或 ..";
        }
        if (!TOPIC_NAME.matcher(topic).matches()) {
            return "Topic 只允许字母、数字、点、下划线、连字符，最长 249 字符";
        }
        return null;
    }

    /**
     * 关闭惰性创建的 {@code Admin}（若已创建）。
     */
    @PreDestroy
    @Override
    public void close() {
        Admin client = this.admin;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.error("关闭 Kafka Admin 失败", e);
            }
            this.admin = null;
        }
    }

    private Admin admin() {
        Admin client = this.admin;
        if (client == null) {
            synchronized (this) {
                client = this.admin;
                if (client == null) {
                    Properties properties = new Properties();
                    properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                                   props.getKafka().getBootstrapServers());
                    client = AdminClient.create(properties);
                    this.admin = client;
                }
            }
        }
        return client;
    }

    private static boolean isUnknownTopic(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof UnknownTopicOrPartitionException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static BusinessException unavailable() {
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, 5061,
                                     "Kafka 元数据不可用，无法校验 Topic");
    }
}
