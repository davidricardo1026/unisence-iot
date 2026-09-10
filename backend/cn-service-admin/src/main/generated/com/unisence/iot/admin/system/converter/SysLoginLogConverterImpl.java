package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysLoginLog;
import com.unisence.iot.admin.system.vo.LoginLogVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysLoginLogConverterImpl implements SysLoginLogConverter {

    @Override
    public LoginLogVO toVO(SysLoginLog entity) {
        if ( entity == null ) {
            return null;
        }

        LoginLogVO loginLogVO = new LoginLogVO();

        loginLogVO.setLoginLogId( entity.getLoginLogId() );
        loginLogVO.setUsername( entity.getUsername() );
        loginLogVO.setIpaddr( entity.getIpaddr() );
        loginLogVO.setLoginLocation( entity.getLoginLocation() );
        loginLogVO.setBrowser( entity.getBrowser() );
        loginLogVO.setOs( entity.getOs() );
        loginLogVO.setStatus( entity.getStatus() );
        loginLogVO.setMsg( entity.getMsg() );
        loginLogVO.setLoginTime( entity.getLoginTime() );

        return loginLogVO;
    }
}
