package com.unisence.iot.admin.rule;

import com.unisence.iot.rule.sdk.RuleRuntimeCapabilityProvider;
import org.springframework.stereotype.Component;

import java.util.ServiceLoader;

/**
 * 从发行制品的实际运行时依赖探测规则能力。
 */
@Component
public final class RuleRuntimeCapabilities {

    private final boolean windowRuntimeInstalled = ServiceLoader.load(RuleRuntimeCapabilityProvider.class)
        .stream()
        .map(ServiceLoader.Provider::get)
        .anyMatch(RuleRuntimeCapabilityProvider::supportsWindowRules);

    public boolean windowRuntimeInstalled() {
        return windowRuntimeInstalled;
    }
}
