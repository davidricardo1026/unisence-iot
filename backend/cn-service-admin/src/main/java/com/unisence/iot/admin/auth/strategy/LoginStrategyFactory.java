package com.unisence.iot.admin.auth.strategy;

import com.unisence.iot.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginStrategyFactory {

    private final Map<String, LoginStrategy> strategyMap = new ConcurrentHashMap<>();

    public LoginStrategyFactory(List<LoginStrategy> strategies) {
        for (LoginStrategy strategy : strategies) {
            strategyMap.put(strategy.getIdentityType(), strategy);
        }
    }

    public LoginStrategy getStrategy(String identityType) {
        LoginStrategy strategy = strategyMap.get(identityType);
        if (strategy == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 1001, "不支持的登录渠道: " + identityType);
        }
        return strategy;
    }
}
