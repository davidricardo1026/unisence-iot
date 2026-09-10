package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysConfig;
import com.unisence.iot.admin.system.dto.ConfigCreateRequest;
import com.unisence.iot.admin.system.dto.ConfigUpdateRequest;
import com.unisence.iot.admin.system.vo.ConfigVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysConfigConverterImpl implements SysConfigConverter {

    @Override
    public ConfigVO toVO(SysConfig config) {
        if ( config == null ) {
            return null;
        }

        ConfigVO configVO = new ConfigVO();

        configVO.setConfigId( config.getConfigId() );
        configVO.setConfigName( config.getConfigName() );
        configVO.setConfigKey( config.getConfigKey() );
        configVO.setConfigValue( config.getConfigValue() );
        configVO.setConfigType( config.getConfigType() );
        configVO.setVersion( config.getVersion() );
        configVO.setCreateTime( config.getCreateTime() );

        return configVO;
    }

    @Override
    public SysConfig toEntity(ConfigCreateRequest request) {
        if ( request == null ) {
            return null;
        }

        SysConfig sysConfig = new SysConfig();

        sysConfig.setConfigName( request.getConfigName() );
        sysConfig.setConfigKey( request.getConfigKey() );
        sysConfig.setConfigValue( request.getConfigValue() );
        sysConfig.setConfigType( request.getConfigType() );

        return sysConfig;
    }

    @Override
    public void updateEntity(SysConfig entity, ConfigUpdateRequest request) {
        if ( request == null ) {
            return;
        }

        entity.setConfigName( request.getConfigName() );
        entity.setConfigValue( request.getConfigValue() );
        entity.setConfigType( request.getConfigType() );
    }
}
