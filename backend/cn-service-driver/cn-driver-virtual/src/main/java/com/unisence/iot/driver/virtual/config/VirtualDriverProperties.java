package com.unisence.iot.driver.virtual.config;

import com.unisence.iot.message.type.NodeType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app.driver.virtual")
public class VirtualDriverProperties {

    private boolean enabled;
    private String environment = "TEST";
    private String source = "cn-driver-virtual";
    private Duration initialDelay = Duration.ofSeconds(1);
    private List<Device> devices = new ArrayList<>();
    private Load load = new Load();

    public Load getLoad() {
        return load;
    }

    public void setLoad(Load load) {
        this.load = load == null ? new Load() : load;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        this.initialDelay = initialDelay;
    }

    public List<Device> getDevices() {
        return devices;
    }

    public void setDevices(List<Device> devices) {
        this.devices = devices == null ? new ArrayList<>() : devices;
    }

    /**
     * 压测负载模式（driver-runtime-design.md §十）。
     *
     * <p>与 {@link Device} 列表<b>互斥</b>：{@code enabled=true} 时完全不读 {@code devices[]}。
     * 第一版的「每设备一个 scheduleWithFixedDelay」形态撑不起 10 万设备，
     * 这里改为「固定线程数 + 定速取模选设备」。
     */
    public static class Load {
        private boolean enabled;
        private String productKey;
        private int deviceCount = 100_000;
        private String deviceCodePrefix = "dev-";
        private int propertyCount = 20;
        /**
         * 属性标识符白名单。为空时按 {@code prop0..prop{count-1}} 生成。
         *
         * <p><b>压已有产品时必须显式配置</b>：生成的 {@code propN} 不在物模型里，
         * 会被 engine 的物模型校验整条拒掉 —— 那样压测测的是校验拒绝路径。
         * 配置后 {@code property-count} 失效，以本列表长度为准。
         */
        private List<String> propertyIdentifiers = new ArrayList<>();

        /**
         * 事件消息在总负载中的占比（0~1）。<b>默认 0，即不改变既有测法</b>。
         *
         * <p>存在的理由：压测负载此前只构造 device_create 与 property 两种消息，
         * {@code EventIngestionVerticle} 的事件分支从未被覆盖，
         * 而它与属性链路形态不同（params 是嵌套 map、解码更重）。
         */
        private double eventRatio = 0d;

        /**
         * 事件标识符池，逗号分隔。必须是该产品物模型里真实存在的事件标识符，
         * 否则会被物模型校验整条拒掉，测到的是 DLQ 路径。
         */
        private List<String> eventIdentifiers = new ArrayList<>();

        /**
         * 事件参数声明，格式 {@code event1:p1|p2,event2:p3}。
         * 键与值都必须是物模型里声明的标识符，否则整条被 EventValidator 拒绝。
         */
        private String eventParams = "";

        /**
         * 设备心跳在总负载中的占比（0~1）。默认 0。
         *
         * <p>心跳的唯一职责是续租（`EventIngestionVerticle` 的 DeviceHeartbeatMessage 分支），
         * 它与属性/事件走**同一个** {@code renew} 路径，因此本项主要用于覆盖
         * 「没有业务数据时的保底活性信号」这条语义，而非压吞吐。
         */
        private double deviceHeartbeatRatio = 0d;

        /**
         * 服务心跳（驱动实例租约）的发送周期，0 表示不发。
         *
         * <p><b>不按比例混入而是按周期发</b>：它表达的是「驱动实例还活着」，
         * 一个实例一个周期一条，与设备数和消息量都无关。契约要求
         * {@code service-ttl-seconds} ≥ 该周期的 2~3 倍。
         */
        private Duration serviceHeartbeatInterval = Duration.ZERO;
        private double propertyMin = 0d;
        private double propertyMax = 100d;
        private int propertyScale = 2;
        private int targetRps;
        private int publisherThreads = 8;
        private int maxInFlight = 20_000;
        private Duration warmup = Duration.ofSeconds(30);
        private Duration duration = Duration.ZERO;
        private Duration reportInterval = Duration.ofSeconds(10);
        private int hotDeviceCount;
        private double hotDeviceShare;
        /**
         * 建档消息的 {@code formData}。
         *
         * <p><b>必须覆盖产品 {@code device_form_schema} 里的全部必填字段</b>，
         * 否则建档会被以「必填字段缺失: xxx」整批投 DLQ，设备建不出来，
         * 随后的属性又因「设备不存在」二次进 DLQ —— 压测全程测的是 DLQ 路径。
         */
        private Map<String, Object> formData = new LinkedHashMap<>();
        private Provision provision = new Provision();

        public Map<String, Object> getFormData() {
            return formData;
        }

        public void setFormData(Map<String, Object> formData) {
            this.formData = formData == null ? new LinkedHashMap<>() : formData;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProductKey() {
            return productKey;
        }

        public void setProductKey(String productKey) {
            this.productKey = productKey;
        }

        public int getDeviceCount() {
            return deviceCount;
        }

        public void setDeviceCount(int deviceCount) {
            this.deviceCount = deviceCount;
        }

        public String getDeviceCodePrefix() {
            return deviceCodePrefix;
        }

        public void setDeviceCodePrefix(String deviceCodePrefix) {
            this.deviceCodePrefix = deviceCodePrefix;
        }

        public int getPropertyCount() {
            return propertyCount;
        }

        public double getDeviceHeartbeatRatio() {
            return deviceHeartbeatRatio;
        }

        public void setDeviceHeartbeatRatio(double deviceHeartbeatRatio) {
            this.deviceHeartbeatRatio = deviceHeartbeatRatio;
        }

        public Duration getServiceHeartbeatInterval() {
            return serviceHeartbeatInterval;
        }

        public void setServiceHeartbeatInterval(Duration serviceHeartbeatInterval) {
            this.serviceHeartbeatInterval = serviceHeartbeatInterval;
        }

        public String getEventParams() {
            return eventParams;
        }

        public void setEventParams(String eventParams) {
            this.eventParams = eventParams;
        }

        public double getEventRatio() {
            return eventRatio;
        }

        public void setEventRatio(double eventRatio) {
            this.eventRatio = eventRatio;
        }

        public List<String> getEventIdentifiers() {
            return eventIdentifiers;
        }

        public void setEventIdentifiers(List<String> eventIdentifiers) {
            this.eventIdentifiers = eventIdentifiers;
        }

        public void setPropertyCount(int propertyCount) {
            this.propertyCount = propertyCount;
        }

        public List<String> getPropertyIdentifiers() {
            return propertyIdentifiers;
        }

        public void setPropertyIdentifiers(List<String> propertyIdentifiers) {
            this.propertyIdentifiers = propertyIdentifiers == null ? new ArrayList<>() : propertyIdentifiers;
        }

        public double getPropertyMin() {
            return propertyMin;
        }

        public void setPropertyMin(double propertyMin) {
            this.propertyMin = propertyMin;
        }

        public double getPropertyMax() {
            return propertyMax;
        }

        public void setPropertyMax(double propertyMax) {
            this.propertyMax = propertyMax;
        }

        public int getPropertyScale() {
            return propertyScale;
        }

        public void setPropertyScale(int propertyScale) {
            this.propertyScale = propertyScale;
        }

        public int getTargetRps() {
            return targetRps;
        }

        public void setTargetRps(int targetRps) {
            this.targetRps = targetRps;
        }

        public int getPublisherThreads() {
            return publisherThreads;
        }

        public void setPublisherThreads(int publisherThreads) {
            this.publisherThreads = publisherThreads;
        }

        public int getMaxInFlight() {
            return maxInFlight;
        }

        public void setMaxInFlight(int maxInFlight) {
            this.maxInFlight = maxInFlight;
        }

        public Duration getWarmup() {
            return warmup;
        }

        public void setWarmup(Duration warmup) {
            this.warmup = warmup;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(Duration duration) {
            this.duration = duration;
        }

        public Duration getReportInterval() {
            return reportInterval;
        }

        public void setReportInterval(Duration reportInterval) {
            this.reportInterval = reportInterval;
        }

        public int getHotDeviceCount() {
            return hotDeviceCount;
        }

        public void setHotDeviceCount(int hotDeviceCount) {
            this.hotDeviceCount = hotDeviceCount;
        }

        public double getHotDeviceShare() {
            return hotDeviceShare;
        }

        public void setHotDeviceShare(double hotDeviceShare) {
            this.hotDeviceShare = hotDeviceShare;
        }

        public Provision getProvision() {
            return provision;
        }

        public void setProvision(Provision provision) {
            this.provision = provision == null ? new Provision() : provision;
        }
    }

    /**
     * 压属性前的批量建档。
     *
     * <p><b>不是可选项</b>：设备存在性是数据面准入条件（metadata-sync-bus.md §6.7bis），
     * 未建档设备的属性会被 engine 丢弃、被 rule-stream 投 DLQ ——
     * 跳过建档的压测测的是丢弃路径，不是规则链路。
     */
    public static class Provision {
        private boolean enabled = true;
        private int rps = 2000;
        private Duration settleWait = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getRps() {
            return rps;
        }

        public void setRps(int rps) {
            this.rps = rps;
        }

        public Duration getSettleWait() {
            return settleWait;
        }

        public void setSettleWait(Duration settleWait) {
            this.settleWait = settleWait;
        }
    }

    public static class Device {
        private boolean enabled = true;
        private String productKey;
        private String deviceCode;
        private String deviceName;
        private NodeType nodeType = NodeType.DIRECT;
        private String gatewayCode;
        private Map<String, Object> formData = new LinkedHashMap<>();
        private Duration propertyInterval;
        private Map<String, RandomValue> properties = new LinkedHashMap<>();
        private Duration eventInterval;
        private List<Event> events = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProductKey() {
            return productKey;
        }

        public void setProductKey(String productKey) {
            this.productKey = productKey;
        }

        public String getDeviceCode() {
            return deviceCode;
        }

        public void setDeviceCode(String deviceCode) {
            this.deviceCode = deviceCode;
        }

        public String getDeviceName() {
            return deviceName;
        }

        public void setDeviceName(String deviceName) {
            this.deviceName = deviceName;
        }

        public NodeType getNodeType() {
            return nodeType;
        }

        public void setNodeType(NodeType nodeType) {
            this.nodeType = nodeType;
        }

        public String getGatewayCode() {
            return gatewayCode;
        }

        public void setGatewayCode(String gatewayCode) {
            this.gatewayCode = gatewayCode;
        }

        public Map<String, Object> getFormData() {
            return formData;
        }

        public void setFormData(Map<String, Object> formData) {
            this.formData = formData == null ? new LinkedHashMap<>() : formData;
        }

        public Duration getPropertyInterval() {
            return propertyInterval;
        }

        public void setPropertyInterval(Duration propertyInterval) {
            this.propertyInterval = propertyInterval;
        }

        public Map<String, RandomValue> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, RandomValue> properties) {
            this.properties = properties == null ? new LinkedHashMap<>() : properties;
        }

        public Duration getEventInterval() {
            return eventInterval;
        }

        public void setEventInterval(Duration eventInterval) {
            this.eventInterval = eventInterval;
        }

        public List<Event> getEvents() {
            return events;
        }

        public void setEvents(List<Event> events) {
            this.events = events == null ? new ArrayList<>() : events;
        }
    }

    public static class RandomValue {
        private double min;
        private double max;
        private int scale = 2;

        public double getMin() {
            return min;
        }

        public void setMin(double min) {
            this.min = min;
        }

        public double getMax() {
            return max;
        }

        public void setMax(double max) {
            this.max = max;
        }

        public int getScale() {
            return scale;
        }

        public void setScale(int scale) {
            this.scale = scale;
        }
    }

    public static class Event {
        private String identifier;
        private Map<String, Object> params = new LinkedHashMap<>();

        public String getIdentifier() {
            return identifier;
        }

        public void setIdentifier(String identifier) {
            this.identifier = identifier;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params == null ? new LinkedHashMap<>() : params;
        }
    }
}
