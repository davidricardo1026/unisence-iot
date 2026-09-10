package com.unisence.iot.init.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unisence.iot.rule.compiler.CompiledRuleScript;
import com.unisence.iot.rule.compiler.RuleScriptCompiler;
import com.unisence.iot.rule.config.CompileResult;
import com.unisence.iot.rule.config.KafkaOutputPurpose;
import com.unisence.iot.rule.config.OutputFormat;
import com.unisence.iot.rule.sdk.RuleFilter;
import com.unisence.iot.rule.sdk.RuleOutput;
import com.unisence.iot.rule.sdk.RuleScriptLimits;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;

/**
 * 新库初始化的规则种子写入器。
 *
 * <p>本服务只由 {@link SeedDataFillerService} 的新库事务调用。即时/窗口脚本在任何 DML 之前
 * 使用运行时同源的 {@link RuleScriptCompiler} 预编译；透传路由无脚本。规则行、档位、输出绑定
 * 和产品绑定随后作为同一事务的一部分落库。初始化发生在任何 engine/rule-stream 实例启动之前，
 * 因此规则直接启用，由运行实例首次全量引导读取，不额外制造元数据增量提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleSeedDataService {

    private static final String KIND_INSTANT = "INSTANT";
    private static final String KIND_WINDOW = "WINDOW";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("unchecked")
    public void fillSeedRules(Map<String, Long> productIdsByKey) {
        Map<String, Object> root;
        try (InputStream input = new ClassPathResource("data/rules.json").getInputStream()) {
            root = objectMapper.readValue(input, Map.class);
        } catch (Exception error) {
            throw new IllegalStateException("读取规则种子 data/rules.json 失败", error);
        }

        List<Map<String, Object>> instantRules = children(root, "instantRules");
        List<Map<String, Object>> windowRules = children(root, "windowRules");
        List<Map<String, Object>> routeRules = children(root, "routeRules");
        Map<String, SeededOutput> outputsByCode = seedKafkaOutputs(root);
        log.info("Initializing rule seeds: instant={}, window={}, route={}, kafkaOutputs={}",
                 instantRules.size(), windowRules.size(), routeRules.size(), outputsByCode.size());

        try (RuleScriptCompiler compiler = new RuleScriptCompiler(RuleScriptLimits.DEFAULT)) {
            for (Map<String, Object> rule : instantRules) {
                insertRule(KIND_INSTANT, rule, productIdsByKey, outputsByCode, compiler);
            }
            for (Map<String, Object> rule : windowRules) {
                insertRule(KIND_WINDOW, rule, productIdsByKey, outputsByCode, compiler);
            }
        }
        for (Map<String, Object> rule : routeRules) {
            insertRoute(rule, productIdsByKey, outputsByCode);
        }
    }

    private Map<String, SeededOutput> seedKafkaOutputs(Map<String, Object> root) {
        Map<String, SeededOutput> byCode = new LinkedHashMap<>();
        for (Map<String, Object> output : children(root, "kafkaOutputs")) {
            String code = text(output, "outputCode");
            KafkaOutputPurpose purpose = parsePurpose(text(output, "purpose"));
            parseFormat(text(output, "format"));
            SeededOutput existing = jdbcTemplate.query(
                "SELECT output_id, purpose FROM us_iot_rule_kafka_output WHERE output_code = ? AND deleted = 0",
                rs -> rs.next()
                    ? new SeededOutput(rs.getLong("output_id"), parsePurpose(rs.getString("purpose")))
                    : null,
                code
            );
            if (existing != null) {
                byCode.put(code, existing);
                log.info("Kafka output seed already exists, skip: code={} outputId={}", code, existing.outputId());
                continue;
            }
            Long outputId = insertReturningKey(
                "INSERT INTO us_iot_rule_kafka_output "
                    + "(output_code, output_name, purpose, target_topic, format) VALUES (?, ?, ?, ?, ?)",
                code,
                text(output, "outputName"),
                purpose.name(),
                text(output, "targetTopic"),
                text(output, "format")
            );
            byCode.put(code, new SeededOutput(outputId, purpose));
            log.info("Initialized Kafka output seed: code={} outputId={} purpose={} topic={}",
                     code, outputId, purpose, text(output, "targetTopic"));
        }
        return byCode;
    }

    private void insertRule(String kind, Map<String, Object> rule, Map<String, Long> productIdsByKey,
                            Map<String, SeededOutput> outputsByCode, RuleScriptCompiler compiler) {
        String ruleCode = text(rule, "ruleCode");
        String filterScript = text(rule, "filterScript");
        List<Map<String, Object>> levels = children(rule, "levels").stream()
            .sorted(Comparator.comparingInt(level -> number(level, "severity").intValue()))
            .toList();
        if (levels.isEmpty()) {
            throw new IllegalArgumentException("规则种子至少需要一个档位: " + ruleCode);
        }

        CompiledSeed compiled = compile(ruleCode, filterScript, levels, compiler);
        Long ruleId = KIND_INSTANT.equals(kind)
            ? insertInstant(rule, compiled)
            : insertWindow(rule, compiled);

        for (Map<String, Object> level : levels) {
            Long levelId = insertReturningKey(
                "INSERT INTO us_iot_rule_level "
                    + "(rule_kind, rule_id, level_code, severity, condition_kind, threshold_config, "
                    + "condition_script, output_script, cooldown_millis) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                kind,
                ruleId,
                text(level, "levelCode"),
                number(level, "severity").intValue(),
                text(level, "conditionKind"),
                json(level.get("thresholdConfig")),
                level.get("conditionScript"),
                text(level, "outputScript"),
                level.get("cooldownMillis")
            );
            List<String> outputCodes = strings(level, "kafkaOutputIds");
            if (outputCodes.isEmpty()) {
                throw new IllegalArgumentException(
                    "规则种子档位必须绑定 Kafka 输出: " + ruleCode + "/" + text(level, "levelCode"));
            }
            for (String outputCode : outputCodes) {
                SeededOutput output = requireOutput(outputsByCode,
                                                    ruleCode,
                                                    outputCode,
                                                    KafkaOutputPurpose.RULE_OUTPUT);
                jdbcTemplate.update(
                    "INSERT INTO us_iot_rule_level_kafka_output (level_id, output_id) VALUES (?, ?)",
                    levelId, output.outputId()
                );
            }
        }

        Set<String> productKeys = new LinkedHashSet<>(strings(rule, "productKeys"));
        if (productKeys.isEmpty()) {
            throw new IllegalArgumentException("规则种子必须绑定至少一个产品: " + ruleCode);
        }
        String bindingTable = KIND_INSTANT.equals(kind)
            ? "us_iot_rule_instant_product" : "us_iot_rule_window_product";
        for (String productKey : productKeys) {
            Long productId = productIdsByKey.get(productKey);
            if (productId == null) {
                throw new IllegalArgumentException(
                    "规则种子引用了未初始化的 productKey: rule=" + ruleCode + ", productKey=" + productKey);
            }
            jdbcTemplate.update(
                "INSERT INTO " + bindingTable + " (rule_id, product_id) VALUES (?, ?)",
                ruleId, productId
            );
        }
        log.info("Initialized enabled rule seed: kind={} ruleId={} ruleCode={} products={} sha256={}",
                 kind, ruleId, ruleCode, productKeys, compiled.scriptSha256());
    }

    private void insertRoute(Map<String, Object> rule, Map<String, Long> productIdsByKey,
                             Map<String, SeededOutput> outputsByCode) {
        String ruleCode = text(rule, "ruleCode");
        String messageType = text(rule, "messageType");
        if (!"property".equals(messageType) && !"event".equals(messageType)) {
            throw new IllegalArgumentException("透传路由种子 messageType 只能是 property 或 event: " + ruleCode);
        }
        List<String> outputCodes = strings(rule, "kafkaOutputIds");
        if (outputCodes.isEmpty()) {
            throw new IllegalArgumentException("透传路由种子必须绑定 Kafka 输出: " + ruleCode);
        }
        Set<String> productKeys = new LinkedHashSet<>(strings(rule, "productKeys"));
        if (productKeys.isEmpty()) {
            throw new IllegalArgumentException("透传路由种子必须绑定至少一个产品: " + ruleCode);
        }

        Long ruleId = insertReturningKey(
            "INSERT INTO us_iot_rule_route (rule_code, rule_name, message_type, status) VALUES (?, ?, ?, 1)",
            ruleCode,
            text(rule, "ruleName"),
            messageType
        );
        for (String outputCode : outputCodes) {
            SeededOutput output = requireOutput(outputsByCode, ruleCode, outputCode, KafkaOutputPurpose.ROUTE);
            jdbcTemplate.update(
                "INSERT INTO us_iot_rule_route_kafka_output (rule_id, output_id) VALUES (?, ?)",
                ruleId, output.outputId()
            );
        }
        for (String productKey : productKeys) {
            Long productId = productIdsByKey.get(productKey);
            if (productId == null) {
                throw new IllegalArgumentException(
                    "透传路由种子引用了未初始化的 productKey: rule=" + ruleCode + ", productKey=" + productKey);
            }
            jdbcTemplate.update(
                "INSERT INTO us_iot_rule_route_product (rule_id, product_id) VALUES (?, ?)",
                ruleId, productId
            );
        }
        log.info("Initialized enabled route seed: ruleId={} ruleCode={} messageType={} products={}",
                 ruleId, ruleCode, messageType, productKeys);
    }

    private static SeededOutput requireOutput(Map<String, SeededOutput> outputsByCode, String ruleCode,
                                              String outputCode, KafkaOutputPurpose expected) {
        SeededOutput output = outputsByCode.get(outputCode);
        if (output == null) {
            throw new IllegalArgumentException(
                "规则种子引用了未知的 Kafka 输出: rule=" + ruleCode + ", output=" + outputCode);
        }
        if (output.purpose() != expected) {
            throw new IllegalArgumentException(
                "规则种子 Kafka 输出用途不匹配: rule=" + ruleCode + ", output=" + outputCode
                    + ", expected=" + expected + ", actual=" + output.purpose());
        }
        return output;
    }

    private static KafkaOutputPurpose parsePurpose(String purpose) {
        try {
            return KafkaOutputPurpose.valueOf(purpose);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("规则种子 Kafka 输出 purpose 非法: " + purpose, error);
        }
    }

    private static void parseFormat(String format) {
        try {
            OutputFormat.valueOf(format);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("规则种子 Kafka 输出 format 非法: " + format, error);
        }
    }

    private Long insertInstant(Map<String, Object> rule, CompiledSeed compiled) {
        return insertReturningKey(
            "INSERT INTO us_iot_rule_instant "
                + "(rule_code, rule_name, message_type, listener_config, value_config, emit_mode, filter_script, "
                + "script_sha256, compile_result, error_policy, status, revision) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)",
            text(rule, "ruleCode"),
            text(rule, "ruleName"),
            text(rule, "messageType"),
            json(rule.get("listenerConfig")),
            json(rule.get("valueConfig")),
            rule.getOrDefault("emitMode", "LEVEL_TRANSITION"),
            text(rule, "filterScript"),
            compiled.scriptSha256(),
            json(compiled.compileResult()),
            rule.getOrDefault("errorPolicy", "DLQ_MESSAGE")
        );
    }

    private Long insertWindow(Map<String, Object> rule, CompiledSeed compiled) {
        return insertReturningKey(
            "INSERT INTO us_iot_rule_window "
                + "(rule_code, rule_name, message_type, listener_config, window_config, aggregate_config, "
                + "filter_script, script_sha256, compile_result, error_policy, status, revision) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1)",
            text(rule, "ruleCode"),
            text(rule, "ruleName"),
            text(rule, "messageType"),
            json(rule.get("listenerConfig")),
            json(rule.get("windowConfig")),
            json(rule.get("aggregateConfig")),
            text(rule, "filterScript"),
            compiled.scriptSha256(),
            json(compiled.compileResult()),
            rule.getOrDefault("errorPolicy", "DLQ_MESSAGE")
        );
    }

    private static CompiledSeed compile(String ruleCode, String filterScript,
                                        List<Map<String, Object>> levels, RuleScriptCompiler compiler) {
        List<String> material = new ArrayList<>(levels.size() * 2 + 1);
        material.add(filterScript);
        for (Map<String, Object> level : levels) {
            material.add((String) level.get("conditionScript"));
            material.add(text(level, "outputScript"));
        }
        String sha256 = RuleScriptCompiler.scriptSha256(material);
        String cacheKey = "seed:" + ruleCode + ":" + sha256;

        CompiledRuleScript<RuleFilter> filter = compiler.compileFilter(cacheKey, filterScript);
        Set<String> identifiers = new LinkedHashSet<>(filter.stats().referencedIdentifiers());
        int outputChars = 0;
        int outputAstNodes = 0;
        for (int index = 0; index < levels.size(); index++) {
            Map<String, Object> level = levels.get(index);
            String conditionScript = (String) level.get("conditionScript");
            if ("SCRIPT".equals(text(level, "conditionKind"))) {
                CompiledRuleScript<RuleFilter> condition =
                    compiler.compileCondition(cacheKey + ":condition:" + index, conditionScript);
                identifiers.addAll(condition.stats().referencedIdentifiers());
                outputAstNodes += condition.stats().astNodes();
            }
            String outputScript = text(level, "outputScript");
            CompiledRuleScript<RuleOutput> output =
                compiler.compileOutput(cacheKey + ":output:" + index, outputScript);
            identifiers.addAll(output.stats().referencedIdentifiers());
            outputChars += outputScript.length();
            outputAstNodes += output.stats().astNodes();
        }

        CompileResult result = new CompileResult(
            compiler.groovyVersion(),
            sha256,
            filter.compiledAt(),
            filterScript.length(),
            outputChars,
            filter.stats().astNodes(),
            outputAstNodes,
            List.copyOf(identifiers),
            filter.sandboxProfile()
        );
        return new CompiledSeed(sha256, result);
    }

    private Long insertReturningKey(String sql, Object... params) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < params.length; index++) {
                statement.setObject(index + 1, params[index]);
            }
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("规则种子 INSERT 未返回主键: " + sql);
        }
        return key.longValue();
    }

    private String json(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("规则种子 JSON 序列化失败", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static List<String> strings(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static String text(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("规则种子字段必填: " + key);
        }
        return text;
    }

    private static Number number(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("规则种子数值字段必填: " + key);
        }
        return number;
    }

    private record CompiledSeed(String scriptSha256, CompileResult compileResult) {
    }

    private record SeededOutput(long outputId, KafkaOutputPurpose purpose) {
    }
}
