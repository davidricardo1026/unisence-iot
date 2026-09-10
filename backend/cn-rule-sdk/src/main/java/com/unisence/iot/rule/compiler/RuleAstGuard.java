package com.unisence.iot.rule.compiler;

import com.unisence.iot.rule.sdk.RuleScriptErrorCode;
import com.unisence.iot.rule.sdk.RuleScriptException;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.AttributeExpression;
import org.codehaus.groovy.ast.expr.BinaryExpression;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.MethodPointerExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.ast.expr.TernaryExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

import java.util.Set;

/**
 * 补 {@code SecureASTCustomizer} 的两处盲区：
 *
 * <ol>
 *   <li><b>方法名黑名单</b>。SecureASTCustomizer 按接收者<i>类型</i>放行，无法按方法名拦截。
 *       {@code 10000000.times{}}、{@code 1.upto(n){}} 是变相无限循环，
 *       {@code getClass()/metaClass} 是逃逸出沙箱类型闭包的常见起点。</li>
 *   <li><b>AST 复杂度上限</b>。防止用超长表达式链拖垮编译与执行。</li>
 * </ol>
 */
public final class RuleAstGuard extends CompilationCustomizer {

    /**
     * 变相循环、脚本自身逃逸、元编程与阻塞调用。
     *
     * <p>刻意<b>不</b>收录 {@code join}/{@code start}/{@code getProperties}：
     * 前两者的危险形态在 Thread 上，而 Thread 因零 import 根本无法被命名，
     * 收录反而会误伤 {@code list.join(',')}；{@code getProperties} 会误伤
     * {@code ThingModelSnapshot.properties()} 这类合法访问器。
     */
    static final Set<String> FORBIDDEN_METHODS = Set.of(
        // 变相循环：10000000.times{} 等价于无限循环
        "times", "upto", "downto", "step",
        // 脚本自身逃逸：Script 基类继承而来，可递归自身或求值任意脚本
        "run", "evaluate", "getBinding", "setBinding", "invokeMethod", "getProperty", "setProperty",
        // 标准输出：规则脚本不得直接写日志或控制台
        "print", "println", "printf",
        // 元编程与反射逃逸：ctx.getClass().getClassLoader() 是最短逃逸路径
        "getClass", "getMetaClass", "setMetaClass", "getClassLoader", "getDeclaringClass",
        "newInstance", "parseClass", "parse",
        // 进程与阻塞：Groovy 的 String.execute() 直接起 shell
        "execute", "exec", "getRuntime", "exit", "halt", "sleep", "wait", "notify", "notifyAll",
        // 调试输出可泄露内部结构
        "dump", "inspect");

    /**
     * 当次编译的统计收集器。
     *
     * <p>用 {@code ThreadLocal} 而不是实例字段：本 customizer 由
     * {@code RuleSandbox.configuration(...)} 创建后被同一个 {@code GroovyClassLoader}
     * <b>跨编译复用</b>，存实例状态会让并发编译互相污染计数。
     * 而 {@code loader.parseClass()} 是在调用线程上同步完成的，因此 ThreadLocal 恰好覆盖一次编译。
     */
    private static final ThreadLocal<ScriptStats.Collector> COLLECTOR = new ThreadLocal<>();

    private final int maxAstNodes;

    public RuleAstGuard(int maxAstNodes) {
        super(CompilePhase.SEMANTIC_ANALYSIS);
        this.maxAstNodes = maxAstNodes;
    }

    /**
     * 由 {@link RuleScriptCompiler} 在每次 parse 前后成对调用。
     */
    static void beginCollect() {
        COLLECTOR.set(new ScriptStats.Collector());
    }

    /**
     * 取回统计并清理 ThreadLocal；必须放在 finally 里，否则线程复用会串数据。
     */
    static ScriptStats endCollect() {
        ScriptStats.Collector collector = COLLECTOR.get();
        COLLECTOR.remove();
        return collector == null ? new ScriptStats(0, java.util.List.of()) : collector.toStats();
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        classNode.visitContents(new GuardVisitor(source, maxAstNodes));
    }

    private static final class GuardVisitor extends ClassCodeVisitorSupport {

        private final SourceUnit sourceUnit;
        private final int maxAstNodes;
        private int nodes;

        private GuardVisitor(SourceUnit sourceUnit, int maxAstNodes) {
            this.sourceUnit = sourceUnit;
            this.maxAstNodes = maxAstNodes;
        }

        @Override
        protected SourceUnit getSourceUnit() {
            return sourceUnit;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
            countNode();
            reject(call.getMethodAsString(), call.getLineNumber(), call.getColumnNumber());
            super.visitMethodCallExpression(call);
        }

        @Override
        public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
            countNode();
            reject(call.getMethod(), call.getLineNumber(), call.getColumnNumber());
            super.visitStaticMethodCallExpression(call);
        }

        @Override
        public void visitPropertyExpression(PropertyExpression expression) {
            countNode();
            // ctx.metaClass / ctx.class 这类属性写法等价于对应的 getter 调用
            reject(toGetter(expression.getPropertyAsString()),
                   expression.getLineNumber(), expression.getColumnNumber());
            super.visitPropertyExpression(expression);
        }

        @Override
        public void visitAttributeExpression(AttributeExpression expression) {
            countNode();
            super.visitAttributeExpression(expression);
        }

        @Override
        public void visitMethodPointerExpression(MethodPointerExpression expression) {
            countNode();
            // 方法引用 &foo 可绕过调用点检查，直接禁止
            throw forbidden("方法引用(&)", expression.getLineNumber(), expression.getColumnNumber());
        }

        @Override
        public void visitBinaryExpression(BinaryExpression expression) {
            countNode();
            super.visitBinaryExpression(expression);
        }

        @Override
        public void visitTernaryExpression(TernaryExpression expression) {
            countNode();
            super.visitTernaryExpression(expression);
        }

        @Override
        public void visitClosureExpression(ClosureExpression expression) {
            countNode();
            super.visitClosureExpression(expression);
        }

        @Override
        public void visitListExpression(ListExpression expression) {
            countNode();
            super.visitListExpression(expression);
        }

        @Override
        public void visitMapExpression(MapExpression expression) {
            countNode();
            super.visitMapExpression(expression);
        }

        @Override
        public void visitConstantExpression(ConstantExpression expression) {
            countNode();
            if (expression.getValue() instanceof String literal) {
                ScriptStats.Collector collector = COLLECTOR.get();
                if (collector != null) {
                    collector.recordLiteral(literal);
                }
            }
            super.visitConstantExpression(expression);
        }

        @Override
        public void visitVariableExpression(VariableExpression expression) {
            countNode();
            super.visitVariableExpression(expression);
        }

        @Override
        protected void visitStatement(Statement statement) {
            countNode();
            super.visitStatement(statement);
        }

        private void countNode() {
            ScriptStats.Collector collector = COLLECTOR.get();
            if (collector != null) {
                collector.countNode();
            }
            if (++nodes > maxAstNodes) {
                throw new RuleScriptException(RuleScriptErrorCode.SCRIPT_TOO_COMPLEX,
                                              "脚本 AST 节点数超出上限 " + maxAstNodes);
            }
        }

        private void reject(String methodName, int line, int column) {
            if (methodName != null && FORBIDDEN_METHODS.contains(methodName)) {
                throw forbidden(methodName + "()", line, column);
            }
        }

        private static RuleScriptException forbidden(String what, int line, int column) {
            return new RuleScriptException(RuleScriptErrorCode.SCRIPT_FORBIDDEN_SYNTAX,
                                           "脚本禁止使用 " + what + " (行 " + line + ", 列 " + column + ")");
        }

        /**
         * {@code ctx.metaClass} 归一为 {@code getMetaClass}，与方法调用写法用同一份黑名单。
         */
        private static String toGetter(String property) {
            if (property == null || property.isEmpty()) {
                return null;
            }
            return "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        }
    }
}
