package com.unisence.iot.rule.compiler;

import com.unisence.iot.rule.sdk.RuleScriptLimits;
import groovy.transform.CompileStatic;
import groovy.transform.ThreadInterrupt;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.ForStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;

import java.util.List;

/**
 * 规则脚本编译沙箱。
 *
 * <p>分层防护（detailed-design.md §3.5）：
 * <ol>
 *   <li><b>零 import + 间接 import 检查</b>——这是最大的一道杠杆。脚本既不能 import 任何类，
 *       也不能用 {@code java.lang.System.exit(0)} 这样的全限定名绕过，
 *       因此可触达的类型闭包被压缩为「基类属性 + 字面量 + 从 SDK 返回值可达的类型」。</li>
 *   <li><b>禁止方法定义</b>——杜绝用户自定义递归，同时防止覆盖 final 入口方法之外的钩子。</li>
 *   <li><b>禁止循环语句</b>——见下方说明。</li>
 *   <li><b>{@link RuleAstGuard}</b>——按方法名拦截变相循环与元编程逃逸。</li>
 *   <li><b>{@code @CompileStatic}</b>——关闭运行时动态派发，顺带在保存期完成类型检查。</li>
 *   <li><b>隔离 ClassLoader + 独立低权限进程</b>——最终边界，见 {@link RuleScriptCompiler}。</li>
 * </ol>
 */
public final class RuleSandbox {

    /**
     * 危险接收者黑名单。零 import 之后这些类已无法被命名，此处只是纵深防御。
     *
     * <p>刻意使用<b>黑名单而非白名单</b>：{@code setAllowedReceivers} 依据的是
     * CANONICALIZATION 阶段静态推断出的接收者类型，而该阶段大量表达式的推断结果是 Object，
     * 白名单会把合法的 SDK 调用一并拒掉。类型闭包已由「零 import + 间接 import 检查 + @CompileStatic」保证。
     */
    private static final List<Class> DISALLOWED_RECEIVERS = List.of(
        // 最短逃逸路径
        System.class, Runtime.class, ProcessBuilder.class, Process.class,
        Class.class, ClassLoader.class, Thread.class, ThreadLocal.class, ThreadGroup.class,
        java.io.File.class, java.lang.reflect.Array.class,
        // 破坏重放可确定性：同一条消息重放必须得到同一结果
        java.util.Random.class, java.util.Timer.class,
        // Groovy 元编程与动态求值入口
        groovy.lang.GroovySystem.class, groovy.lang.GroovyShell.class,
        groovy.lang.GroovyClassLoader.class, groovy.lang.MetaClassRegistry.class);

    /**
     * 脚本可触达的包闭包：SDK 自身，加上 SDK 访问器实际会返回的 JDK 类型
     * （String/Boolean/Double 等在 java.lang，Map/Set/List 在 java.util，BigDecimal 在 java.math），
     * 以及 GString 等 Groovy 字面量类型所在的 groovy.lang。此外一律越权。
     */
    private static final List<String> ALLOWED_PACKAGES = List.of(
        "com.unisence.iot.rule.sdk",
        // 共享值域枚举（MessageType 等）；同包的 IotMessage 系列不在此列，
        // 由 RuleSdkClassLoader 的黑名单挡住（device-message-contract.md §八）
        "com.unisence.iot.message.type",
        "java.lang",
        "java.util",
        "java.math",
        "groovy.lang");

    /**
     * 允许作为实例方法调用接收者的类型。{@code java.lang.Object} 必须在列，
     * 原因见 {@link #configuration} 中的说明；其余是 SDK 访问器实际会返回的类型。
     *
     * <p>String 在列并不意味着放行 {@code 'ls'.execute()} —— 那由 {@link RuleAstGuard} 的方法名黑名单拦截。
     */
    private static final List<String> ALLOWED_RECEIVER_TYPES = List.of(
        "java.lang.Object",
        "java.lang.String",
        "java.lang.CharSequence",
        "java.lang.Boolean",
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Double",
        "java.lang.Number",
        "java.lang.Math",
        "java.lang.Comparable",
        "java.math.BigDecimal",
        "java.util.Map",
        "java.util.List",
        "java.util.Set",
        "java.util.Collection",
        "groovy.lang.GString");

    private RuleSandbox() {
    }

    /**
     * {@code @ThreadInterrupt} 注入。
     *
     * <p>{@code applyToAllClasses / applyToAllMembers} 必须开：闭包是禁循环后<b>唯一还能迭代</b>
     * 的结构，漏掉它就等于没注入 —— 而失控脚本恰恰只可能出现在闭包里。
     *
     * <p>这条与类注释第 3 点（禁止循环语句）里「线程中断无法可靠终止恶意循环」的判断并不矛盾：
     * 那句针对的是<b>没有注入检查点</b>的字节码循环，中断标志确实无人读取。
     * 注入之后检查点由编译器保证存在，中断才成为可用手段。两道防线一起用，
     * 而不是用其中一道替代另一道。
     */
    private static ASTTransformationCustomizer threadInterrupt() {
        ASTTransformationCustomizer customizer =
            new ASTTransformationCustomizer(ThreadInterrupt.class);
        customizer.setAnnotationParameters(java.util.Map.of(
            "checkOnMethodStart", true,
            "applyToAllClasses", true,
            "applyToAllMembers", true));
        return customizer;
    }

    /**
     * @param scriptBaseClass {@link RuleFilterScript} 或 {@link RuleOutputScript}
     */
    public static CompilerConfiguration configuration(Class<?> scriptBaseClass, RuleScriptLimits limits) {
        CompilerConfiguration configuration = new CompilerConfiguration();
        configuration.setScriptBaseClass(scriptBaseClass.getName());
        configuration.setSourceEncoding("UTF-8");

        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setPackageAllowed(false);
        secure.setMethodDefinitionAllowed(false);
        secure.setClosuresAllowed(true);
        secure.setIndirectImportCheckEnabled(true);

        // 包级白名单：开启 indirectImportCheck 后，该白名单同时约束「import 语句」与
        // 「表达式的静态类型」。因此不能设成空列表——那会连 ctx.numberValue() 的返回类型都判为越权。
        // 白名单必须覆盖 SDK 自身，以及 SDK 访问器可能返回的 JDK 类型。
        secure.setAllowedStarImports(ALLOWED_PACKAGES);

        // 静态导入白名单同时被 indirectImportCheck 用来校验「实例方法调用的接收者类型」。
        // SecureASTCustomizer 运行在 CANONICALIZATION 阶段，早于 @CompileStatic 的类型推断，
        // 此时 ctx.numberValue() 的接收者类型只能推断为 java.lang.Object，
        // 因此白名单必须显式含 Object，否则一切合法 SDK 调用都会被判越权。
        // 真正的静态调用（java.lang.System.exit）走 StaticMethodCallExpression 分支，
        // 用的是已解析的 owner 类型，不受此条影响，仍被拦截。
        secure.setAllowedStaticImports(List.of());
        secure.setAllowedStaticStarImports(ALLOWED_RECEIVER_TYPES);

        // 注意：SecureASTCustomizer 不允许 import 类目同时设白名单与黑名单
        // （setDisallowedImports 会直接抛 IllegalArgumentException）。既然包级白名单更强，
        // 白名单里残留的危险类改由 setDisallowedReceiversClasses 这一独立类目挖掉，见 DISALLOWED_RECEIVERS。

        // 禁止一切循环语句：detailed-design.md §3.5 已确认线程中断无法可靠终止恶意循环，
        // 因此不能把超时当作唯一防线。禁掉 while/for/do-while 后，脚本执行步数由 AST 结构静态封顶，
        // 集合遍历只能用闭包，其迭代次数受 payload 大小限制约束。
        secure.setDisallowedStatements(List.<Class<? extends Statement>>of(
            WhileStatement.class, ForStatement.class, DoWhileStatement.class));
        secure.setDisallowedReceiversClasses(DISALLOWED_RECEIVERS);

        configuration.addCompilationCustomizers(
            secure,
            new RuleAstGuard(limits.maxAstNodes()),
            // 在方法与闭包入口注入中断检查，使执行方能用 interrupt 真正终止跑飞的脚本，
            // 而不是只能事后作废结果。与「禁循环」互补而非冲突：
            // 禁循环把恶意脚本压成有界的慢，中断处理「有界但仍然太慢」——
            // 闭包遍历一个超大集合正落在这个区间
            threadInterrupt(),
            new ASTTransformationCustomizer(CompileStatic.class));
        return configuration;
    }
}
