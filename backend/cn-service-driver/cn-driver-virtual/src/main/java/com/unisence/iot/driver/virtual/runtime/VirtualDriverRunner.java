package com.unisence.iot.driver.virtual.runtime;

import com.unisence.iot.driver.common.kafka.DeviceDataPublisher;
import com.unisence.iot.driver.virtual.config.VirtualDriverProperties;
import com.unisence.iot.message.DeviceCreateMessage;
import com.unisence.iot.message.DeviceEventMessage;
import com.unisence.iot.message.DevicePropertyMessage;
import com.unisence.iot.message.IotMessage;
import com.unisence.iot.message.codec.MessagePackMessageCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class VirtualDriverRunner implements ApplicationRunner, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VirtualDriverRunner.class);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private final VirtualDriverProperties properties;
    private final DeviceDataPublisher publisher;
    private final Set<CompletableFuture<?>> pendingPublishes = ConcurrentHashMap.newKeySet();
    private final long deliveryEpoch = System.currentTimeMillis();
    private final ConcurrentMap<String, AtomicLong> deliverySequences = new ConcurrentHashMap<>();
    private ScheduledThreadPoolExecutor scheduler;
    private LoadGenerator loadGenerator;

    public VirtualDriverRunner(VirtualDriverProperties properties, DeviceDataPublisher publisher) {
        this.properties = properties;
        this.publisher = publisher;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("虚拟驱动未启用");
            return;
        }
        requireValidEnvironment();
        // 压测模式与联调模式互斥（driver-runtime-design.md §十）：
        // devices[] 的「每设备一个 scheduleWithFixedDelay」形态撑不起 10 万设备
        if (properties.getLoad().isEnabled()) {
            log.info("虚拟驱动进入**压测负载模式**，devices[] 配置将被忽略");
            loadGenerator = new LoadGenerator(properties, publisher);
            loadGenerator.start();
            return;
        }
        List<VirtualDriverProperties.Device> devices = validateAndGetEnabledDevices();
        scheduler = new ScheduledThreadPoolExecutor(
            Math.min(4, Math.max(1, devices.size())),
            new VirtualDriverThreadFactory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.schedule(() -> startDevices(devices),
                           properties.getInitialDelay().toMillis(), TimeUnit.MILLISECONDS);
        log.info("虚拟驱动已启动: devices={} initialDelay={}", devices.size(), properties.getInitialDelay());
    }

    /**
     * 环境护栏对<b>两种模式都适用</b> —— 压测模式同样禁止在 DEV/TEST 之外启用，
     * 否则它会朝生产 Topic 灌 10 万台虚拟设备。
     */
    private void requireValidEnvironment() {
        String environment = requireText(properties.getEnvironment(), "app.driver.virtual.environment")
            .toUpperCase(Locale.ROOT);
        if (!environment.equals("DEV") && !environment.equals("TEST")) {
            throw new IllegalArgumentException("app.driver.virtual.environment 仅允许 DEV 或 TEST");
        }
        requireText(properties.getSource(), "app.driver.virtual.source");
    }

    private List<VirtualDriverProperties.Device> validateAndGetEnabledDevices() {
        requireNonNegative(properties.getInitialDelay(), "app.driver.virtual.initial-delay");

        List<VirtualDriverProperties.Device> devices = properties.getDevices().stream()
            .filter(VirtualDriverProperties.Device::isEnabled)
            .toList();
        if (devices.isEmpty()) {
            throw new IllegalArgumentException("虚拟驱动启用时至少需要一个 enabled=true 的设备");
        }

        Set<String> identities = new HashSet<>();
        for (int i = 0; i < devices.size(); i++) {
            VirtualDriverProperties.Device device = devices.get(i);
            String path = "app.driver.virtual.devices[" + i + "]";
            validateDevice(device, path);
            String identity = device.getProductKey() + '\0' + device.getDeviceCode();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(path + " 的 product-key/device-code 重复");
            }
        }
        return devices;
    }

    private void validateDevice(VirtualDriverProperties.Device device, String path) {
        if (device.getNodeType() == null) {
            throw new IllegalArgumentException(path + ".node-type 不能为空");
        }
        // 复用消息契约做设备身份、名称、网关关系及 formData 的最终结构校验。
        try {
            newCreateMessage(device);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(path + " 不符合设备创建消息契约: " + error.getMessage(), error);
        }

        boolean hasProperties = !device.getProperties().isEmpty();
        if (hasProperties != (device.getPropertyInterval() != null)) {
            throw new IllegalArgumentException(path + ".property-interval 与 properties 必须同时配置");
        }
        if (hasProperties) {
            requirePositive(device.getPropertyInterval(), path + ".property-interval");
            device.getProperties().forEach((identifier, range) -> {
                requireText(identifier, path + ".properties 的 identifier");
                if (range == null || !Double.isFinite(range.getMin()) || !Double.isFinite(range.getMax())
                    || range.getMin() > range.getMax()) {
                    throw new IllegalArgumentException(path + ".properties." + identifier + " 的 min/max 无效");
                }
                if (range.getScale() < 0 || range.getScale() > 6) {
                    throw new IllegalArgumentException(path + ".properties." + identifier + ".scale 必须在 0..6");
                }
            });
        }

        boolean hasEvents = !device.getEvents().isEmpty();
        if (hasEvents != (device.getEventInterval() != null)) {
            throw new IllegalArgumentException(path + ".event-interval 与 events 必须同时配置");
        }
        if (hasEvents) {
            requirePositive(device.getEventInterval(), path + ".event-interval");
            for (int i = 0; i < device.getEvents().size(); i++) {
                try {
                    newEventMessage(device, device.getEvents().get(i));
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(path + ".events[" + i
                                                           + "] 不符合事件消息契约: " + error.getMessage(), error);
                }
            }
        }
    }

    private void startDevices(List<VirtualDriverProperties.Device> devices) {
        for (VirtualDriverProperties.Device device : devices) {
            safeRun(device, "create", () -> publish(newCreateMessage(device)));
            schedule(device, "property", device.getPropertyInterval(), () -> publish(newPropertyMessage(device)));
            schedule(device, "event", device.getEventInterval(), () -> publish(newRandomEventMessage(device)));
        }
    }

    private void schedule(VirtualDriverProperties.Device device, String taskName,
                          Duration interval, Runnable task) {
        if (interval == null) {
            return;
        }
        long millis = interval.toMillis();
        scheduler.scheduleWithFixedDelay(
            () -> safeRun(device, taskName, task), millis, millis, TimeUnit.MILLISECONDS);
    }

    private void safeRun(VirtualDriverProperties.Device device, String taskName, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException error) {
            log.error("虚拟驱动任务失败: productKey={} deviceCode={} task={} errorClass={}",
                      device.getProductKey(), device.getDeviceCode(), taskName,
                      error.getClass().getName(), error);
        }
    }

    private DeviceCreateMessage newCreateMessage(VirtualDriverProperties.Device device) {
        return new DeviceCreateMessage(
            MessagePackMessageCodec.SCHEMA_VERSION, newMsgId(), System.currentTimeMillis(),
            properties.getSource(), device.getProductKey(), device.getDeviceCode(),
            device.getDeviceName(), device.getNodeType(), device.getGatewayCode(), device.getFormData());
    }

    private DevicePropertyMessage newPropertyMessage(VirtualDriverProperties.Device device) {
        Map<String, Object> values = new LinkedHashMap<>();
        device.getProperties().forEach((identifier, range) -> values.put(identifier, randomValue(range)));
        return new DevicePropertyMessage(
            MessagePackMessageCodec.SCHEMA_VERSION, newMsgId(), System.currentTimeMillis(),
            properties.getSource(), deliveryEpoch, nextSequence(device, "property"),
            device.getProductKey(), device.getDeviceCode(), values);
    }

    private DeviceEventMessage newRandomEventMessage(VirtualDriverProperties.Device device) {
        List<VirtualDriverProperties.Event> events = device.getEvents();
        return newEventMessage(device, events.get(ThreadLocalRandom.current().nextInt(events.size())));
    }

    private DeviceEventMessage newEventMessage(VirtualDriverProperties.Device device,
                                               VirtualDriverProperties.Event event) {
        return new DeviceEventMessage(
            MessagePackMessageCodec.SCHEMA_VERSION, newMsgId(), System.currentTimeMillis(),
            properties.getSource(), deliveryEpoch, nextSequence(device, "event"),
            device.getProductKey(), device.getDeviceCode(),
            event == null ? null : event.getIdentifier(), event == null ? null : event.getParams());
    }

    private long nextSequence(VirtualDriverProperties.Device device, String messageType) {
        String key = device.getProductKey() + '\0' + device.getDeviceCode() + '\0' + messageType;
        return deliverySequences.computeIfAbsent(key, ignored -> new AtomicLong()).getAndIncrement();
    }

    private double randomValue(VirtualDriverProperties.RandomValue range) {
        double raw = range.getMin() == range.getMax()
            ? range.getMin()
            : ThreadLocalRandom.current().nextDouble(range.getMin(), range.getMax());
        double factor = Math.pow(10, range.getScale());
        return Math.round(raw * factor) / factor;
    }

    private void publish(IotMessage message) {
        CompletableFuture<?> future = publisher.publish(message);
        pendingPublishes.add(future);
        future.whenComplete((ignored, error) -> pendingPublishes.remove(future));
    }

    private static String newMsgId() {
        return UUID.randomUUID().toString();
    }

    private static String requireText(String value, String path) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(path + " 不能为空");
        }
        return value;
    }

    private static void requirePositive(Duration value, String path) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(path + " 必须大于 0");
        }
    }

    private static void requireNonNegative(Duration value, String path) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(path + " 不能小于 0");
        }
    }

    @Override
    public void close() {
        if (loadGenerator != null) {
            loadGenerator.close();
        }
        if (scheduler == null) {
            return;
        }
        scheduler.shutdownNow();
        CompletableFuture<?>[] pending = pendingPublishes.toArray(CompletableFuture[]::new);
        if (pending.length == 0) {
            return;
        }
        try {
            CompletableFuture.allOf(pending).get(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            log.warn("等待虚拟驱动发送完成被中断: pending={}", pendingPublishes.size());
        } catch (ExecutionException | TimeoutException error) {
            log.warn("等待虚拟驱动发送完成超时或中断: pending={} errorClass={}",
                     pendingPublishes.size(), error.getClass().getName());
        }
    }

    private static final class VirtualDriverThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "virtual-driver-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        }
    }
}
