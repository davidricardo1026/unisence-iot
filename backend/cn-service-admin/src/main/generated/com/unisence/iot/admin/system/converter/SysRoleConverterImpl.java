package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysRole;
import com.unisence.iot.admin.system.dto.RoleCreateRequest;
import com.unisence.iot.admin.system.dto.RoleUpdateRequest;
import com.unisence.iot.admin.system.vo.RoleVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysRoleConverterImpl implements SysRoleConverter {

    @Override
    public RoleVO toVO(SysRole role) {
        if ( role == null ) {
            return null;
        }

        RoleVO roleVO = new RoleVO();

        roleVO.setRoleId( role.getRoleId() );
        roleVO.setRoleName( role.getRoleName() );
        roleVO.setRoleCode( role.getRoleCode() );
        roleVO.setStatus( role.getStatus() );
        roleVO.setCreateTime( role.getCreateTime() );
        roleVO.setVersion( role.getVersion() );

        return roleVO;
    }

    @Override
    public SysRole toEntity(RoleCreateRequest request) {
        if ( request == null ) {
            return null;
        }

        SysRole sysRole = new SysRole();

        sysRole.setRoleName( request.getRoleName() );
        sysRole.setRoleCode( request.getRoleCode() );
        sysRole.setStatus( request.getStatus() );

        return sysRole;
    }

    @Override
    public void updateEntity(SysRole entity, RoleUpdateRequest request) {
        if ( request == null ) {
            return;
        }

        entity.setRoleName( request.getRoleName() );
        entity.setRoleCode( request.getRoleCode() );
        entity.setStatus( request.getStatus() );
    }
}
