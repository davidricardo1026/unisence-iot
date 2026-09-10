package com.unisence.iot.rule.compiler;

import com.unisence.iot.message.type.MessageType;
import com.unisence.iot.rule.sdk.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则脚本编译器与沙箱契约测试。
 * 对齐 harness/backend/business-nodes/rule-engine-module/groovy-sdk-contract.md。
 */
class RuleScriptCompilerTest {

    private static RuleScriptCompiler compiler;

    @BeforeAll
    static void setUp() {
        compiler = new RuleScriptCompiler(RuleScriptLimits.DEFAULT);
    }

    @AfterAll
    static void tearDown() {
        compiler.close();
    }

    // ---------------- 正常路径 ----------------

    @Test
    @DisplayName("裸脚本体可编译并按类型化访问器求值")
    void filterEvaluatesBareExpression() {
        RuleFilter filter = compileFilter("ctx.numberValue('temperature', -999d) > 80d");

        assertTrue(filter.filter(context(Map.of("temperature", 95.5))));
        assertFalse(filter.filter(context(Map.of("temperature", 20.0))));
        assertFalse(filter.filter(context(Map.of())), "缺值时走默认值 -999，不得抛异常");
    }

    @Test
    @DisplayName("filter 可访问设备与物模型快照")
    void filterReadsSnapshots() {
        RuleFilter filter = compileFilter(
            "ctx.device().status() == 1 && ctx.thingModel().hasProperty('temperature')");

        assertTrue(filter.filter(context(Map.of("temperature", 1.0))));
    }

    @Test
    @DisplayName("output 返回 Map 并归一 GString")
    void outputReturnsNormalizedMap() {
        RuleOutput output = compileOutput("""
                                              [type: 'temperature_high',
                                               value: ctx.numberValue('temperature', 0d),
                                               count: trigger.count(),
                                               label: "device-${ctx.deviceCode()}"]
                                              """);

        Map<String, Object> payload = output.output(context(Map.of("temperature", 90.0)), trigger());

        assertEquals("temperature_high", payload.get("type"));
        assertEquals(90.0, payload.get("value"));
        assertEquals(3L, payload.get("count"));
        assertEquals("device-dev-001", payload.get("label"));
        assertEquals(String.class, payload.get("label").getClass(), "GString 必须已归一为 String");
    }

    @Test
    @DisplayName("每次执行必须是新实例，脚本状态不跨消息共享")
    void newInstancePerExecution() {
        CompiledRuleScript<RuleFilter> compiled =
            compiler.compileFilter("rule:1:hash", "ctx.numberValue('temperature', 0d) > 1d");

        assertNotSame(compiled.newInstance(), compiled.newInstance());
    }

    // ---------------- 严格返回类型 ----------------

    @Test
    @DisplayName("filter 返回非 Boolean 必须报错，禁用 Groovy 真值性")
    void filterRejectsNonBooleanReturn() {
        RuleFilter filter = compileFilter("ctx.stringValue('name')");

        RuleScriptException error = assertThrows(RuleScriptException.class,
                                                 () -> filter.filter(context(Map.of())));
        assertEquals(RuleScriptErrorCode.FILTER_RETURN_TYPE, error.errorCode());
    }

    @Test
    @DisplayName("空字符串在 Groovy 中为假值，但此处必须报错而不是静默判为不匹配")
    void filterRejectsFalsyString() {
        RuleFilter filter = compileFilter("''");

        assertThrows(RuleScriptException.class, () -> filter.filter(context(Map.of())));
    }

    @Test
    @DisplayName("output 返回非 Map 必须报错")
    void outputRejectsNonMapReturn() {
        RuleOutput output = compileOutput("ctx.deviceCode()");

        RuleScriptException error = assertThrows(RuleScriptException.class,
                                                 () -> output.output(context(Map.of()), trigger()));
        assertEquals(RuleScriptErrorCode.OUTPUT_RETURN_TYPE, error.errorCode());
    }

    // ---------------- 沙箱 ----------------

    @Test
    @DisplayName("禁止 import")
    void rejectsImport() {
        assertCompileRejected("import java.io.File\ntrue");
    }

    @Test
    @DisplayName("禁止全限定名绕过 import 检查")
    void rejectsIndirectImport() {
        assertCompileRejected("java.lang.System.exit(0); true");
    }

    @Test
    @DisplayName("禁止 while 循环")
    void rejectsWhileLoop() {
        assertCompileRejected("int i = 0; while (true) { i++ }; true");
    }

    @Test
    @DisplayName("禁止 for 循环")
    void rejectsForLoop() {
        assertCompileRejected("for (int i = 0; i < 10; i++) { }; true");
    }

    @Test
    @DisplayName("禁止 times 变相循环")
    void rejectsTimes() {
        assertCompileRejected("10000000.times { }; true");
    }

    @Test
    @DisplayName("禁止方法定义，杜绝用户自定义递归")
    void rejectsMethodDefinition() {
        assertCompileRejected("def loop(int n) { n > 0 ? loop(n - 1) : true }\nloop(1000000)");
    }

    @Test
    @DisplayName("禁止 getClass 元编程逃逸")
    void rejectsGetClass() {
        assertCompileRejected("ctx.getClass().getClassLoader() != null");
    }

    @Test
    @DisplayName("禁止 String.execute() 起进程")
    void rejectsExecute() {
        assertCompileRejected("'ls'.execute(); true");
    }

    @Test
    @DisplayName("禁止脚本自身 evaluate 求值任意代码")
    void rejectsEvaluate() {
        assertCompileRejected("evaluate('1 + 1'); true");
    }

    @Test
    @DisplayName("@CompileStatic：不存在的访问器在编译期即报错，而非上线后运行时炸")
    void rejectsUnknownAccessorAtCompileTime() {
        RuleScriptException error = assertThrows(RuleScriptException.class,
                                                 () -> compiler.compileFilter("rule:1:h", "ctx.temperatureee() > 1d"));
        assertEquals(RuleScriptErrorCode.SCRIPT_COMPILE_FAILED, error.errorCode());
    }

    @Test
    @DisplayName("超长脚本按 SCRIPT_TOO_LONG 拒绝")
    void rejectsTooLongScript() {
        String script = "true " + "/* padding */".repeat(400);

        RuleScriptException error = assertThrows(RuleScriptException.class,
                                                 () -> compiler.compileFilter("rule:1:h", script));
        assertEquals(RuleScriptErrorCode.SCRIPT_TOO_LONG, error.errorCode());
    }

    // ---------------- SHA-256 规范化 ----------------

    @Test
    @DisplayName("仅换行与行尾空白差异不产生新的 scriptSha256")
    void sha256IgnoresInsignificantWhitespace() {
        String a = RuleScriptCompiler.scriptSha256(List.of("ctx.numberValue('t', 0d) > 1d  ", "[a: 1]"));
        String b = RuleScriptCompiler.scriptSha256(List.of("ctx.numberValue('t', 0d) > 1d\r\n", "[a: 1]\n"));

        assertEquals(a, b);
        assertEquals(64, a.length());
    }

    @Test
    @DisplayName("脚本内容变化必须产生不同 scriptSha256")
    void sha256ChangesWithContent() {
        assertNotEquals(
            RuleScriptCompiler.scriptSha256(List.of("ctx.numberValue('t', 0d) > 1d", "[a: 1]")),
            RuleScriptCompiler.scriptSha256(List.of("ctx.numberValue('t', 0d) > 2d", "[a: 1]")));
    }

    @Test
    @DisplayName("脚本顺序变化必须产生不同 scriptSha256：顺序是判档顺序，不是展示偏好")
    void sha256ChangesWithOrder() {
        assertNotEquals(
            RuleScriptCompiler.scriptSha256(List.of("true", "[a: 1]", "[b: 2]")),
            RuleScriptCompiler.scriptSha256(List.of("true", "[b: 2]", "[a: 1]")));
    }

    // ---------------- 夹具 ----------------

    private static RuleFilter compileFilter(String script) {
        return compiler.compileFilter("rule:1:test", script).newInstance();
    }

    private static RuleOutput compileOutput(String script) {
        return compiler.compileOutput("rule:1:test", script).newInstance();
    }

    private static void assertCompileRejected(String script) {
        RuleScriptException error = assertThrows(RuleScriptException.class,
                                                 () -> compiler.compileFilter("rule:1:test", script));
        assertTrue(error.code() >= 5001 && error.code() <= 5004,
                   "应落在编译期错误码区间, 实际=" + error.code() + " " + error.getMessage());
    }

    private static MessageContext context(Map<String, Object> values) {
        MessageEnvelope envelope = new MessageEnvelope(1, "msg-001", "abc123", "dev-001",
                                                       1_700_000_000_000L, "driver-mqtt", MessageType.PROPERTY, null);
        ProductSnapshot product = new ProductSnapshot(1L, "abc123", "温度计", 1, 1, "ACME", "T-100");
        DeviceSnapshot device = new DeviceSnapshot(10L, "dev-001", "一号温度计", null, 1, 1,
                                                   null, null, Map.of());
        ThingModelSnapshot thingModel = new ThingModelSnapshot(
            Map.of("temperature", new PropertyDefinition("temperature", "温度", PropertyDataType.FLOAT, 1, "℃", 180)),
            Map.of());
        return DefaultMessageContext.ofProperty(envelope, KafkaMeta.NONE, values,
                                                product, device, thingModel, 1_700_000_000_500L);
    }

    private static TriggerResult trigger() {
        return new TriggerResult(1L, 1L, FireType.LEVEL_RAISE,
                                 "MAJOR", 20, null,
                                 true, 1_700_000_000_000L, 1_700_000_060_000L,
                                 StateScope.DEVICE, "1:1:DEVICE:abc123:dev-001:w1", 1_700_000_060_000L,
                                 AggregateResult.ofCount(3L));
    }
}
