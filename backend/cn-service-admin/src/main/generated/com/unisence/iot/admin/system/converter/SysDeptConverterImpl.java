package com.unisence.iot.admin.system.converter;

import com.unisence.iot.admin.entity.SysDept;
import com.unisence.iot.admin.system.dto.DeptCreateRequest;
import com.unisence.iot.admin.system.dto.DeptUpdateRequest;
import com.unisence.iot.admin.system.vo.DeptTreeVO;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-06-13T14:53:40+0800",
    comments = "version: 1.6.3, compiler: javac, environment: Java 21.0.9 (Arch Linux)"
)
@Component
public class SysDeptConverterImpl implements SysDeptConverter {

    @Override
    public DeptTreeVO toTreeNode(SysDept dept) {
        if ( dept == null ) {
            return null;
        }

        DeptTreeVO deptTreeVO = new DeptTreeVO();

        deptTreeVO.setDeptId( dept.getDeptId() );
        deptTreeVO.setParentId( dept.getParentId() );
        deptTreeVO.setDeptName( dept.getDeptName() );
        deptTreeVO.setSortOrder( dept.getSortOrder() );
        deptTreeVO.setLeader( dept.getLeader() );
        deptTreeVO.setPhone( dept.getPhone() );
        deptTreeVO.setStatus( dept.getStatus() );
        deptTreeVO.setVersion( dept.getVersion() );

        return deptTreeVO;
    }

    @Override
    public SysDept toEntity(DeptCreateRequest request) {
        if ( request == null ) {
            return null;
        }

        SysDept sysDept = new SysDept();

        sysDept.setParentId( request.getParentId() );
        sysDept.setDeptName( request.getDeptName() );
        sysDept.setSortOrder( request.getSortOrder() );
        sysDept.setLeader( request.getLeader() );
        sysDept.setPhone( request.getPhone() );
        sysDept.setStatus( request.getStatus() );

        return sysDept;
    }

    @Override
    public void updateEntity(SysDept entity, DeptUpdateRequest request) {
        if ( request == null ) {
            return;
        }

        entity.setParentId( request.getParentId() );
        entity.setDeptName( request.getDeptName() );
        entity.setSortOrder( request.getSortOrder() );
        entity.setLeader( request.getLeader() );
        entity.setPhone( request.getPhone() );
        entity.setStatus( request.getStatus() );
    }
}
