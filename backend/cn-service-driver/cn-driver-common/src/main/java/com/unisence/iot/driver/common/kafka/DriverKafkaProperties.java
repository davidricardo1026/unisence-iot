package com.unisence.iot.driver.common.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.driver.kafka")
public class DriverKafkaProperties {

    private String rawDataTopic = "iot.raw.data";
    private String eventTopic = "iot.event";

    public String getRawDataTopic() {
        return rawDataTopic;
    }

    public void setRawDataTopic(String rawDataTopic) {
        this.rawDataTopic = rawDataTopic;
    }

    public String getEventTopic() {
        return eventTopic;
    }

    public void setEventTopic(String eventTopic) {
        this.eventTopic = eventTopic;
    }
}
