package com.unisence.iot.admin.system.vo.generated.registry;

import io.github.easytrans.core.bridge.BaseTranslationBridge;
import io.github.easytrans.core.registry.TranslationRegistry;
import com.unisence.iot.admin.system.vo.UserVO;
import com.unisence.iot.admin.system.vo.generated.UserVO_TranslationBridge;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component("easytransRegistry_com_unisence_iot_admin_system_vo_generated_registry")
public class GeneratedTranslationRegistry implements TranslationRegistry {

    private final Map<Class<?>, BaseTranslationBridge<?>> byEntityClass;

    public GeneratedTranslationRegistry(UserVO_TranslationBridge userVO_TranslationBridge) {
        Map<Class<?>, BaseTranslationBridge<?>> map = new HashMap<>();
        map.put(UserVO.class, userVO_TranslationBridge);
        this.byEntityClass = Map.copyOf(map);
    }

    @Override
    public BaseTranslationBridge<?> findBridgeByEntityClass(Class<?> entityClass) {
        return byEntityClass.get(entityClass);
    }
}
