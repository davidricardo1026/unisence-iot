package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.system.dto.UserCreateRequest;
import com.unisence.iot.admin.system.dto.UserUpdateRequest;
import com.unisence.iot.admin.system.vo.UserVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysUserConverterImpl implements SysUserConverter {

    @Override
    public UserVO toVO(SysUser user) {
        if ( user == null ) {
            return null;
        }

        UserVO userVO = new UserVO();

        userVO.setUserId( user.getUserId() );
        userVO.setUsername( user.getUsername() );
        userVO.setRealName( user.getRealName() );
        userVO.setPhone( user.getPhone() );
        userVO.setStatus( user.getStatus() );
        userVO.setDeptId( user.getDeptId() );
        userVO.setCreateBy( user.getCreateBy() );
        userVO.setCreateTime( user.getCreateTime() );
        userVO.setVersion( user.getVersion() );

        return userVO;
    }

    @Override
    public SysUser toEntity(UserCreateRequest request) {
        if ( request == null ) {
            return null;
        }

        SysUser sysUser = new SysUser();

        sysUser.setUsername( request.getUsername() );
        sysUser.setRealName( request.getRealName() );
        sysUser.setPhone( request.getPhone() );
        sysUser.setStatus( request.getStatus() );
        sysUser.setDeptId( request.getDeptId() );

        return sysUser;
    }

    @Override
    public void updateEntity(SysUser entity, UserUpdateRequest request) {
        if ( request == null ) {
            return;
        }

        entity.setRealName( request.getRealName() );
        entity.setPhone( request.getPhone() );
        entity.setStatus( request.getStatus() );
        entity.setDeptId( request.getDeptId() );
    }
}
