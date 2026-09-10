package com.unisence.iot.engine.config;

import com.unisence.iot.common.core.profile.AppProfile;
import com.unisence.iot.metadata.MetadataProperties;
import com.unisence.iot.rule.sdk.RuleScriptLimits;
import com.unisence.iot.timeseries.TimeSeriesConfig;
import io.vertx.config.ConfigRetriever;
import io.vertx.config.ConfigRetrieverOptions;
import io.vertx.config.ConfigStoreOptions;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 非 Spring 服务的配置加载（application-configuration.md §1、§4）。
 *
 * <p>用 Vert.x 官方 {@link ConfigRetriever}，按 <b>file(yaml) → env → sys</b> 顺序叠加，后者覆盖前者，
 * 与 Spring 的「YAML 默认值 + 环境变量覆盖」等价。
 *
 * <p>唯一需要自己补的是<b>键名映射</b>：Vert.x 的 env/sys store 把变量按<b>原名平铺</b>在 JsonObject 顶层，
 * 不做 Spring 那样的 relaxed binding。因此取值顺序是
 * 「{@code APP_ENGINE_INGRESS_XXX} 平铺键 → {@code app.engine.ingress.xxx} 平铺键 → YAML 嵌套路径」，
 * 使运维侧的覆盖方式与 Spring 服务保持一致。
 */
@Slf4j
public final class EngineConfigLoader {

    private static final String CONFIG_FILE = "application.yml";
    private static final long LOAD_TIMEOUT_SECONDS = 10;
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");

    private final JsonObject config;

    private EngineConfigLoader(JsonObject config) {
        this.config = config;
    }

    /**
     * 阻塞加载配置。只在 bootstrap 期调用一次，失败即启动失败 ——
     * 缺配置必须炸在启动期，不能拖到第一条消息。
     */
    public static EngineConfigLoader load(Vertx vertx) {
        String profileFile = profileFile();
        ConfigStoreOptions yamlStore = new ConfigStoreOptions()
            .setType("file")
            .setFormat("yaml")
            .setConfig(new JsonObject().put("path", CONFIG_FILE));
        ConfigRetrieverOptions options = new ConfigRetrieverOptions()
            .addStore(yamlStore)
            // profile 覆盖层：application-{profile}.yml。文件必须存在，拼错的 profile 在启动期失败。
            // 顺序必须在 base 之后、env 之前 —— 后加入的 store 覆盖先加入的，
            // 而部署环境的变量必须能盖过 profile 文件（logging-runtime.md §3.1 同源约定）
            .addStore(new ConfigStoreOptions().setType("file").setFormat("yaml")
                          .setConfig(new JsonObject().put("path", profileFile)))
            .addStore(new ConfigStoreOptions().setType("env"))
            .addStore(new ConfigStoreOptions().setType("sys"));

        try {
            JsonObject loaded = ConfigRetriever.create(vertx, options)
                .getConfig()
                .toCompletionStage()
                .toCompletableFuture()
                .get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("已加载 engine 配置: base={} profile={}", CONFIG_FILE, profileFile);
            return new EngineConfigLoader(loaded);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("加载 " + CONFIG_FILE + " 被中断", e);
        } catch (Exception e) {
            log.error("加载 engine 配置失败: base={} profile={}", CONFIG_FILE, profileFile, e);
            throw new IllegalStateException("加载配置失败: base=" + CONFIG_FILE + ", profile=" + profileFile, e);
        }
    }

    /**
     * {@code app.engine.route.*}：透传路由 producer 参数与 Topic 命名空间策略。
     */
    public EngineRouteProperties route() {
        return new EngineRouteProperties(
            stringList("app.engine.route.allowed-topic-prefixes"),
            Set.copyOf(stringList("app.engine.route.forbidden-topics")),
            integer("app.engine.route.linger-ms"),
            string("app.engine.route.compression-type"),
            integer("app.engine.route.request-timeout-ms"),
            integer("app.engine.route.delivery-timeout-ms"));
    }

    public EngineIngressProperties ingress() {
        return new EngineIngressProperties(
            string("app.engine.ingress.bootstrap-servers"),
            string("app.engine.ingress.group-id"),
            optionalString("app.engine.ingress.group-instance-id"),
            string("app.engine.ingress.topics.raw-data"),
            string("app.engine.ingress.topics.event"),
            string("app.engine.ingress.event-group-id"),
            string("app.engine.ingress.topics.dlq"),
            integer("app.engine.ingress.consumer-count"),
            integer("app.engine.ingress.poll.max-records"),
            integer("app.engine.ingress.poll.timeout-ms"),
            integer("app.engine.ingress.poll.fetch-min-bytes"),
            integer("app.engine.ingress.poll.fetch-max-wait-ms"),
            integer("app.engine.ingress.max-property-message-bytes"),
            integer("app.engine.ingress.max-event-message-bytes"),
            string("app.engine.ingress.auto-offset-reset"),
            integer("app.engine.ingress.device-create-batch-size"),
            integer("app.engine.ingress.retry-backoff-ms"),
            integer("app.engine.ingress.max-retry-backoff-ms"));
    }

    public EngineTimeSeriesProperties timeSeries() {
        String prefix = "app.engine.timeseries.";
        Map<String, String> options = new LinkedHashMap<>();
        optionalOption(options, "jdbc-url", prefix + "jdbc-url");
        option(options, "write-timeout-ms", prefix + "write-timeout-ms");
        option(options, "write-max-retries", prefix + "write-max-retries");
        option(options, "max-in-flight-write-points", prefix + "max-in-flight-write-points");
        return new EngineTimeSeriesProperties(
            new TimeSeriesConfig(
                string(prefix + "type"), stringList(prefix + "endpoints"), string(prefix + "database"),
                valueOrEmpty(prefix + "user"), valueOrEmpty(prefix + "password"), integer(prefix + "pool-max-size"),
                integer(prefix + "connection-timeout-ms"), options),
            integer(prefix + "batch-max-rows"), integer(prefix + "batch-max-bytes"),
            integer(prefix + "write-concurrency"),
            integer(prefix + "schema-sync-concurrency"));
    }

    private void option(Map<String, String> target, String option, String key) {
        target.put(option, string(key));
    }

    private void optionalOption(Map<String, String> target, String option, String key) {
        String value = optionalString(key);
        if (value != null && !value.isBlank()) target.put(option, value);
    }

    private String valueOrEmpty(String key) {
        String value = optionalString(key);
        return value == null ? "" : value;
    }

    public EngineMySQLProperties mysql() {
        return new EngineMySQLProperties(
            string("app.engine.mysql.host"),
            integer("app.engine.mysql.port"),
            string("app.engine.mysql.database"),
            string("app.engine.mysql.user"),
            string("app.engine.mysql.password"),
            integer("app.engine.mysql.pool-max-size"),
            integer("app.engine.mysql.max-wait-queue-size"),
            integer("app.engine.mysql.connection-timeout-ms"),
            integer("app.engine.mysql.idle-timeout-ms"),
            bool("app.engine.mysql.cache-prepared-statements"),
            integer("app.engine.mysql.prepared-statement-cache-max-size"),
            integer("app.engine.mysql.batch-size"));
    }

    public EngineOnlineProperties online() {
        return new EngineOnlineProperties(
            integer("app.engine.online.default-ttl-seconds"),
            integer("app.engine.online.service-ttl-seconds"),
            integer("app.engine.online.scan-interval-ms"),
            integer("app.engine.online.scan-batch-size"),
            integer("app.engine.online.scan-shard-concurrency"),
            integer("app.engine.online.scan-lock-ttl-ms"),
            longValue("app.engine.online.renew-throttle-ms"),
            integer("app.engine.online.renew-throttle-max-entries"),
            integer("app.engine.online.renew-flush-batch-size"),
            integer("app.engine.online.renew-flush-delay-ms"),
            integer("app.engine.online.renew-pending-max"),
            integer("app.engine.online.flush-batch-size"),
            integer("app.engine.online.flush-delay-ms"),
            integer("app.engine.online.transition-read-batch-size"),
            longValue("app.engine.online.transition-block-ms"),
            longValue("app.engine.online.transition-claim-idle-ms"),
            integer("app.engine.online.transition-drain-shard-concurrency"),
            integer("app.engine.online.max-pending-transitions-alert"));
    }

    /**
     * {@code app.engine.rule.script.*}（groovy-sdk-contract.md §九）。
     *
     * <p>与 {@code cn-service-admin} 的 {@code app.rule.script.*} 是<b>同一套限制</b>：
     * admin 保存时按它编译校验，engine 刷新快照时按它编译。两侧不一致会让规则保存成功却在运行时被拒，
     * 因此契约规定<b>以 engine 为准</b>。
     */
    public RuleScriptLimits ruleScript() {
        return new RuleScriptLimits(
            integer("app.engine.rule.script.max-filter-script-chars"),
            integer("app.engine.rule.script.max-output-script-chars"),
            integer("app.engine.rule.script.max-ast-nodes"),
            longValue("app.engine.rule.script.filter-timeout-millis"),
            longValue("app.engine.rule.script.output-timeout-millis"),
            integer("app.engine.rule.script.max-output-fields"),
            integer("app.engine.rule.script.max-output-depth"),
            integer("app.engine.rule.script.max-output-bytes"));
    }

    /**
     * {@code app.engine.metadata.*}（metadata-sync-bus.md §十二）。越界即在此抛出，不带病启动。
     */
    public MetadataProperties metadata() {
        return new MetadataProperties(
            longValue("app.engine.metadata.redis-head-probe-interval-ms"),
            longValue("app.engine.metadata.db-reconcile-interval-ms"),
            doubleValue("app.engine.metadata.reconcile-jitter-ratio"),
            longValue("app.engine.metadata.coalesce-delay-ms"),
            longValue("app.engine.metadata.max-coalesce-delay-ms"),
            integer("app.engine.metadata.max-incremental-scopes"),
            longValue("app.engine.metadata.retry-initial-delay-ms"),
            longValue("app.engine.metadata.retry-max-delay-ms"),
            integer("app.engine.metadata.failure-alert-threshold"),
            longValue("app.engine.metadata.instance-heartbeat-interval-ms"),
            longValue("app.engine.metadata.instance-status-ttl-ms"),
            integer("app.engine.metadata.product-max-entries"),
            integer("app.engine.metadata.thing-model-definition-max-entries"),
            integer("app.engine.metadata.rule-max-entries"),
            longValue("app.engine.metadata.bootstrap-small-domain-max-bytes"),
            integer("app.engine.metadata.device-catalog-shard-count"),
            integer("app.engine.metadata.device-catalog-load-page-size"),
            integer("app.engine.metadata.device-catalog-max-entries"),
            doubleValue("app.engine.metadata.device-existence-fpp"),
            integer("app.engine.metadata.device-existence-delta-max-entries"),
            integer("app.engine.metadata.device-existence-rebuild-interval-hours"),
            longValue("app.engine.metadata.device-l1-max-weight-bytes"),
            integer("app.engine.metadata.device-l1-expire-after-access-seconds"),
            integer("app.engine.metadata.device-cache-entry-max-bytes"),
            integer("app.engine.metadata.device-l2-read-batch-size"),
            integer("app.engine.metadata.device-db-fallback-batch-size"),
            integer("app.engine.metadata.device-db-fallback-max-concurrency"),
            integer("app.engine.metadata.device-db-fallback-queue-high-watermark"),
            integer("app.engine.metadata.device-db-fallback-queue-low-watermark"),
            longValue("app.engine.metadata.device-load-timeout-ms"),
            integer("app.engine.metadata.unknown-device-cache-max-entries"),
            longValue("app.engine.metadata.unknown-device-cache-ttl-ms"),
            integer("app.engine.metadata.unknown-device-repair-stripes"),
            integer("app.engine.metadata.unknown-device-repair-max-concurrency"),
            longValue("app.engine.metadata.unknown-device-head-probe-freshness-ms"),
            longValue("app.engine.metadata.unknown-device-reconcile-timeout-ms"));
    }

    public EngineRedisProperties redis() {
        return new EngineRedisProperties(
            string("app.engine.redis.connection-string"),
            integer("app.engine.redis.pool-max-size"),
            integer("app.engine.redis.max-pool-waiting"),
            integer("app.engine.redis.max-waiting-handlers"),
            integer("app.engine.redis.pipeline-batch-size"));
    }

    /**
     * 停机时等待 {@code vertx.close()}（即全部 verticle 的 {@code stop()}）跑完的上限。
     *
     * <p>必须等：{@code vertx.close()} 返回 {@code Future} 且立即返回，不等它就会在
     * verticle 还在 stop 的时候把 MySQL / Redis 关掉，让在途任务撞上已关闭的客户端
     * （{@code vertx-pool-thread-affinity.md} §3.4）。
     *
     * <p>又必须有上限：某个 verticle 的 {@code stop()} 卡住时，无界等待会让进程收到
     * SIGTERM 后永不退出，只能等编排平台 SIGKILL —— 那会连带丢掉本可以正常完成的清理。
     */
    /**
     * 在 {@code Vertx} 实例建立<b>之前</b>读池大小 —— 鸡生蛋问题：
     * {@code ConfigRetriever} 需要 Vertx，而 Vertx 的池大小又来自配置。
     *
     * <p>解法是用一个<b>临时 Vertx</b>只读这两个值，读完立即关闭；
     * 随后主流程再用正式 Vertx 走完整的 {@link #load} 加载全部配置。
     * 代价是一次多余的文件读，换来「池大小同样可由 yml / 环境变量控制」。
     */
    public static int bootstrapVertxWorkerPoolSize() {
        return bootstrapInt("app.engine.vertx.worker-pool-size");
    }

    public static int bootstrapVertxInternalBlockingPoolSize() {
        return bootstrapInt("app.engine.vertx.internal-blocking-pool-size");
    }

    private static int bootstrapInt(String key) {
        Vertx temp = Vertx.vertx();
        try {
            return load(temp).integer(key);
        } finally {
            temp.close();
        }
    }

    /**
     * Vert.x worker 池大小。
     *
     * <p><b>显式化的理由与 rule-stream 的 `replication-factor` 同源</b>：不写此项时取
     * Vert.x 默认 20，而 engine 以 {@code VIRTUAL_THREAD} 部署且全仓无 {@code executeBlocking}，
     * 该池实测<b>用量极低</b>（收紧到 1 条后累计 CPU 仅 9 ticks，`hotpath-findings.md` H15）。
     * 20 条平台线程各约 1MB 栈，纯属过配 —— 但**具体收到多少是阈值**，
     * 且**故障路径（连接重建、DNS 重解析）会用这两个池，收得过小会串行化且后果未测**，
     * 故此处只做「让它在本仓配置里看得见」，取值留 R10.1。
     */
    public int vertxWorkerPoolSize() {
        int value = integer("app.engine.vertx.worker-pool-size");
        if (value <= 0) {
            throw new IllegalStateException("app.engine.vertx.worker-pool-size 必须为正数，当前为 " + value);
        }
        return value;
    }

    /**
     * Vert.x internal-blocking 池大小；理由同 {@link #vertxWorkerPoolSize()}。
     */
    public int vertxInternalBlockingPoolSize() {
        int value = integer("app.engine.vertx.internal-blocking-pool-size");
        if (value <= 0) {
            throw new IllegalStateException(
                "app.engine.vertx.internal-blocking-pool-size 必须为正数，当前为 " + value);
        }
        return value;
    }

    public int shutdownTimeoutMs() {
        int value = integer("app.engine.shutdown-timeout-ms");
        if (value <= 0) {
            throw new IllegalStateException(
                "app.engine.shutdown-timeout-ms 必须为正数，当前为 " + value);
        }
        return value;
    }

    public EngineFieldCryptoProperties fieldCrypto() {
        String currentVersion = string("app.engine.crypto.field.current-version");
        Object configured = resolveNested("app.engine.crypto.field.keys");
        if (!(configured instanceof JsonObject jsonKeys)) {
            throw new IllegalStateException("缺少必需配置（对象）: app.engine.crypto.field.keys");
        }
        Map<String, String> keys = new LinkedHashMap<>();
        jsonKeys.forEach(entry -> keys.put(entry.getKey(), String.valueOf(entry.getValue())));
        String override = optionalString("app.engine.crypto.field.keys." + currentVersion);
        if (override != null) {
            keys.put(currentVersion, override);
        }
        return new EngineFieldCryptoProperties(currentVersion, keys);
    }

    public String string(String path) {
        String value = optionalString(path);
        if (value == null) {
            throw new IllegalStateException(
                "缺少必需配置: " + path + "（或环境变量 " + toEnvName(path) + "）");
        }
        return value;
    }

    /**
     * 与 {@link #string(String)} 相同的查找顺序，但允许缺省，返回 {@code null}。
     */
    public String optionalString(String path) {
        Object value = config.getValue(toEnvName(path));
        if (value == null) {
            value = config.getValue(path);
        }
        if (value == null) {
            value = resolveNested(path);
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.isBlank()) {
            return null;
        }
        // YAML 里的 ${VAR} 是给运维看的占位符，Vert.x 不做插值。走到这里说明同名环境变量没设：
        // 必须炸在启动期，否则会把字面量 "${APP_ENGINE_TIMESERIES_PASSWORD}" 当成口令去连库，
        // 表现为难以定位的认证失败。
        if (UNRESOLVED_PLACEHOLDER.matcher(text).matches()) {
            throw new IllegalStateException(
                "配置 " + path + " 是未解析的占位符 " + text + "，请设置环境变量 " + toEnvName(path));
        }
        return text;
    }

    public int integer(String path) {
        String raw = string(path);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + path + " 不是合法整数: " + raw, e);
        }
    }

    public long longValue(String path) {
        String raw = string(path);
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + path + " 不是合法长整数: " + raw, e);
        }
    }

    public double doubleValue(String path) {
        String raw = string(path);
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("配置 " + path + " 不是合法小数: " + raw, e);
        }
    }

    public boolean bool(String path) {
        return Boolean.parseBoolean(string(path).trim());
    }

    /**
     * YAML 列表；环境变量覆盖时用逗号分隔。
     */
    public List<String> stringList(String path) {
        Object env = config.getValue(toEnvName(path));
        if (env instanceof String text && !text.isBlank()) {
            return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        Object value = config.getValue(path);
        if (value == null) {
            value = resolveNested(path);
        }
        if (value instanceof JsonArray array) {
            return array.stream().map(String::valueOf).toList();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        throw new IllegalStateException("缺少必需配置（列表）: " + path);
    }

    private Object resolveNested(String path) {
        JsonObject current = config;
        String[] segments = path.split("\\.");
        for (int i = 0; i < segments.length - 1; i++) {
            current = current.getJsonObject(segments[i]);
            if (current == null) {
                return null;
            }
        }
        return current.getValue(segments[segments.length - 1]);
    }

    /**
     * {@code app.engine.ingress.bootstrap-servers} → {@code APP_ENGINE_INGRESS_BOOTSTRAP_SERVERS}。
     */
    /**
     * {@code application-{profile}.yml}。
     *
     * <p>profile 文件必须存在；不存在或无法读取时配置加载直接失败，避免拼错后静默使用基础配置。
     */
    private static String profileFile() {
        return "application-" + AppProfile.resolve() + ".yml";
    }

    private static String toEnvName(String path) {
        return path.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }
}
