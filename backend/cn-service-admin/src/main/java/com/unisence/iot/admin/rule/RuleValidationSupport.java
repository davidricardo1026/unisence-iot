package com.unisence.iot.admin.rule;

import com.unisence.iot.admin.entity.IotProduct;
import com.unisence.iot.admin.mapper.IotProductMapper;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.rule.compiler.CompiledRuleScript;
import com.unisence.iot.rule.compiler.RuleScriptCompiler;
import com.unisence.iot.rule.config.*;
import com.unisence.iot.rule.sdk.RuleFilter;
import com.unisence.iot.rule.sdk.RuleOutput;
import com.unisence.iot.rule.sdk.RuleScriptException;
import com.unisence.iot.rule.sdk.ThingModelSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 规则保存前的校验与预编译（delivery-roadmap.md P1.4）。
 *
 * <p><b>为什么必须前置到保存期</b>：脏规则若写进库，engine 会在构建候选根时才发现编译失败，
 * 而候选根是「全成功才安装」的 —— 一条坏规则会让该实例<b>整轮收敛失败</b>并停在 LKG，
 * 连带其它域的变更一起延迟。把失败挡在 HTTP 400 上，代价只是这一次保存被拒。
 *
 * <p><b>identifier 校验对每个绑定产品逐一执行、全部通过才算通过</b>：
 * 一条规则可绑定多个产品，各自物模型不同。只校验其中一个会让规则在另一个产品的消息上
 * 静默失效。规则保存是低频操作，几十次纯内存校验的代价可以忽略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleValidationSupport {

    private final RuleScriptCompiler compiler;
    private final RuleThingModelReader thingModelReader;
    private final IotProductMapper productMapper;
    private final RuleWindowProperties windowProperties;

    /**
     * 预编译产物 + 摘要，供保存事务写库。
     *
     * @param levels 与 {@code definition.levels()} 一一对应、同序（severity 升序）
     */
    public record Compiled(String scriptSha256,
                           CompiledRuleScript<RuleFilter> filter,
                           List<CompiledLevelScripts> levels) {
    }

    /**
     * 一个档位的预编译产物。
     *
     * @param condition {@code conditionKind=SCRIPT} 时非空
     */
    public record CompiledLevelScripts(CompiledRuleScript<RuleFilter> condition,
                                       CompiledRuleScript<RuleOutput> output) {
    }

    /**
     * 完整校验：配置合法性 → 逐产品 identifier 校验 → 沙箱预编译。
     *
     * <p>顺序不可调换：配置非法时脚本编译多半也会失败，先报配置错更接近根因；
     * 而编译是三者中最慢的一步，放在最后可以让大多数错误提前返回。
     *
     * @param boundProductIds 规则当前（或即将）绑定的产品；空集表示暂无绑定，跳过 identifier 校验
     */
    public Compiled validateAndCompile(RuleDefinition definition, List<Long> boundProductIds,
                                       String filterScript, List<LevelScripts> levelScripts) {
        for (Long productId : boundProductIds) {
            ThingModelSnapshot thingModel = thingModelReader.read(productId);
            List<RuleConfigViolation> violations =
                RuleConfigValidator.validate(definition, thingModel, windowProperties.toLimits());
            if (!violations.isEmpty()) {
                RuleConfigViolation first = violations.get(0);
                throw new BusinessException(HttpStatus.BAD_REQUEST, first.code().code(),
                                            "产品[" + productKeyOf(productId) + "] " + first.field() + ": " + first.message());
            }
        }
        if (boundProductIds.isEmpty()) {
            // 尚无绑定：identifier 无从校验，但配置本身（组合矩阵、区间、保留时长）仍必须过
            List<RuleConfigViolation> violations = RuleConfigValidator.validate(definition,
                                                                                new ThingModelSnapshot(java.util.Map.of(),
                                                                                                       java.util.Map.of()),
                                                                                windowProperties.toLimits());
            List<RuleConfigViolation> blocking = violations.stream()
                // 未绑定产品时，identifier 类错误是预期的，不应阻塞保存
                .filter(v -> v.code().code() != 5048 && v.code().code() != 5049)
                .toList();
            if (!blocking.isEmpty()) {
                RuleConfigViolation first = blocking.get(0);
                throw new BusinessException(HttpStatus.BAD_REQUEST, first.code().code(),
                                            first.field() + ": " + first.message());
            }
        }
        return compile(definition, filterScript, levelScripts);
    }

    /**
     * 一个档位的脚本原文。调用方必须按 {@code definition.levels()} 的顺序（severity 升序）传入。
     */
    public record LevelScripts(String conditionScript, String outputScript) {
    }

    /**
     * 沙箱预编译。
     *
     * <p>缓存键用 {@code sha256} 而非 ruleId：同一份脚本改了缩进换行不该产生新键
     * （{@code scriptSha256} 已做规范化），而不同规则用同一份脚本时也能复用。
     *
     * <p><b>摘要覆盖全部脚本且顺序敏感</b>：filter、然后按 severity 升序逐档的
     * condition 与 output。增删档位、调换 severity、改任一脚本都会改变摘要，
     * 从而让 engine 侧的编译缓存失效 —— 这正是「改了规则却不生效」的防线。
     */
    private Compiled compile(RuleDefinition definition, String filterScript,
                             List<LevelScripts> levelScripts) {
        List<String> material = new java.util.ArrayList<>(levelScripts.size() * 2 + 1);
        material.add(filterScript);
        for (LevelScripts scripts : levelScripts) {
            material.add(scripts.conditionScript());
            material.add(scripts.outputScript());
        }
        String sha256 = RuleScriptCompiler.scriptSha256(material);
        String cacheKey = definition.ruleId() + ":validate:" + sha256;
        try {
            CompiledRuleScript<RuleFilter> filter = compiler.compileFilter(cacheKey, filterScript);
            List<CompiledLevelScripts> levels = new java.util.ArrayList<>(levelScripts.size());
            for (int i = 0; i < levelScripts.size(); i++) {
                LevelDefinition level = definition.levels().get(i);
                LevelScripts scripts = levelScripts.get(i);
                levels.add(new CompiledLevelScripts(
                    level.conditionKind() == ConditionKind.SCRIPT
                        ? compiler.compileCondition(cacheKey + ":L" + i, scripts.conditionScript())
                        : null,
                    compiler.compileOutput(cacheKey + ":L" + i, scripts.outputScript())));
            }
            return new Compiled(sha256, filter, levels);
        } catch (RuleScriptException e) {
            // 编译失败是用户输入问题，不是服务端故障：返回 400 并带上 SDK 的具体错误码
            log.warn("规则脚本编译失败: ruleId={} code={} msg={}",
                     definition.ruleId(), e.code(), e.getMessage());
            throw new BusinessException(HttpStatus.BAD_REQUEST, e.code(), e.getMessage());
        }
    }

    private String productKeyOf(Long productId) {
        IotProduct product = productMapper.selectById(productId);
        return product == null ? String.valueOf(productId) : product.getProductKey();
    }

    /**
     * 绑定的产品必须存在且为普通产品（{@code product_type=1}）—— 标准产品是模板，不挂设备。
     */
    public void requireNormalProducts(List<Long> productIds) {
        for (Long productId : productIds) {
            IotProduct product = productMapper.selectById(productId);
            if (product == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 5061, "绑定的产品不存在: " + productId);
            }
            if (!Objects.equals(product.getProductType(), 1)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 5061,
                                            "只能绑定普通产品，标准产品是模板不挂设备: " + product.getProductKey());
            }
        }
    }
}
