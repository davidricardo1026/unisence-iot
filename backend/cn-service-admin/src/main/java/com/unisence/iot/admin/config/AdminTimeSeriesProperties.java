package com.unisence.iot.admin.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "app.admin.timeseries")
public class AdminTimeSeriesProperties {
    @NotBlank
    private String type = "greptime";
    @NotEmpty
    private List<String> endpoints;
    @NotBlank
    private String database = "unisence_iot";
    private String user = "";
    private String password = "";
    private String jdbcUrl;
    @Min(1)
    private int poolMaxSize = 4;
    @Min(1)
    private int connectionTimeoutMs = 3000;
    @Min(1)
    private int writeTimeoutMs = 30000;
    @Min(1)
    @Max(604800000L)
    private long historyMaxRangeMs = 604800000L;
    @Min(4)
    private int historyChartMaxPoints = 1000;
    @Min(1)
    private int propertyRawMaxPageSize = 100;
    @Min(1)
    @Max(604800000L)
    private long eventQueryMaxRangeMs = 604800000L;
    @Min(1)
    private int eventQueryMaxPageSize = 100;
    @Min(1)
    @Max(604800000L)
    private long onlineLogQueryMaxRangeMs = 604800000L;
    @Min(1)
    private int onlineLogQueryMaxPageSize = 100;
    @Min(2)
    private int onlineLogChartMaxPoints = 1000;
}
