package com.unisence.iot.admin.system.vo.generated;

import io.github.easytrans.core.context.TranslationContext;
import io.github.easytrans.core.bridge.BaseTranslationBridge;
import com.unisence.iot.admin.system.vo.UserVO;
import org.springframework.stereotype.Component;
import java.util.List;

@Component("easytransBridge_com_unisence_iot_admin_system_vo_generated_UserVO_TranslationBridge")
public class UserVO_TranslationBridge implements BaseTranslationBridge<UserVO> {

    @Override
    public void extractAllIds(List<UserVO> sources, TranslationContext context) {
        extractIds(sources, context);
    }

    @Override
    public void writeBack(List<UserVO> sources, TranslationContext context) {
        fillTranslations(sources, context);
    }

    public static void extractIds(List<UserVO> sources, TranslationContext context) {
        if (sources == null) return;
        for (UserVO item : sources) {
            extractIds(item, context);
        }
    }

    public static void extractIds(UserVO item, TranslationContext context) {
        if (item == null) return;
        if (item.getDeptId() != null) {
            context.collectId("DEPT_SERVICE", item.getDeptId());
        }
        if (item.getCreateBy() != null) {
            context.collectId("USER_SERVICE", item.getCreateBy());
        }
    }

    public static void fillTranslations(List<UserVO> sources, TranslationContext context) {
        if (sources == null) return;
        for (UserVO item : sources) {
            fillTranslations(item, context);
        }
    }

    public static void fillTranslations(UserVO item, TranslationContext context) {
        if (item == null) return;
        if (item.getDeptId() != null) {
            item.setDeptName(context.getName("DEPT_SERVICE", item.getDeptId()));
        }
        if (item.getCreateBy() != null) {
            item.setCreateByName(context.getName("USER_SERVICE", item.getCreateBy()));
        }
    }
}
