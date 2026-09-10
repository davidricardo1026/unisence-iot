package com.unisence.iot.admin.system.vo.generated;

import io.github.easytrans.core.context.TranslationContext;
import io.github.easytrans.core.bridge.BaseTranslationBridge;
import com.unisence.iot.admin.system.vo.FileInfoVO;
import org.springframework.stereotype.Component;
import java.util.List;

@Component("easytransBridge_com_unisence_iot_admin_system_vo_generated_FileInfoVO_TranslationBridge")
public class FileInfoVO_TranslationBridge implements BaseTranslationBridge<FileInfoVO> {

    @Override
    public void extractAllIds(List<FileInfoVO> sources, TranslationContext context) {
        extractIds(sources, context);
    }

    @Override
    public void writeBack(List<FileInfoVO> sources, TranslationContext context) {
        fillTranslations(sources, context);
    }

    public static void extractIds(List<FileInfoVO> sources, TranslationContext context) {
        if (sources == null) return;
        for (FileInfoVO item : sources) {
            extractIds(item, context);
        }
    }

    public static void extractIds(FileInfoVO item, TranslationContext context) {
        if (item == null) return;
        if (item.getCreateBy() != null) {
            context.collectId("USER_SERVICE", item.getCreateBy());
        }
    }

    public static void fillTranslations(List<FileInfoVO> sources, TranslationContext context) {
        if (sources == null) return;
        for (FileInfoVO item : sources) {
            fillTranslations(item, context);
        }
    }

    public static void fillTranslations(FileInfoVO item, TranslationContext context) {
        if (item == null) return;
        if (item.getCreateBy() != null) {
            item.setCreateByName(context.getName("USER_SERVICE", item.getCreateBy()));
        }
    }
}
