package com.unisence.iot.init.runner;

import com.unisence.iot.init.config.InitProperties;
import com.unisence.iot.init.service.DdlInitializationService;
import com.unisence.iot.init.service.GreptimeInitializationService;
import com.unisence.iot.init.service.SeedDataFillerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseInitializer implements CommandLineRunner {

    private final InitProperties initProperties;
    private final DdlInitializationService ddlInitializationService;
    private final GreptimeInitializationService greptimeInitializationService;
    private final SeedDataFillerService seedDataFillerService;

    @Override
    public void run(String... args) {
        if (!initProperties.isEnabled()) {
            log.info("System initial seed filler is disabled, exiting.");
            return;
        }

        try {
            log.info("Platform database initial checking...");
            ddlInitializationService.initializeEmptyDatabase();
            greptimeInitializationService.initialize();
            seedDataFillerService.fillSeedData();
            log.info("MySQL, GreptimeDB and platform seed data initialized successfully.");
        } catch (Exception e) {
            log.error("Failed to execute database initialization: {}", e.getMessage(), e);
            throw e;
        }
    }
}
