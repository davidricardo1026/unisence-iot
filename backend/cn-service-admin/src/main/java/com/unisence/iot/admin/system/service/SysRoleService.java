package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.RoleCreateRequest;
import com.unisence.iot.admin.system.dto.RoleQuery;
import com.unisence.iot.admin.system.dto.RoleUpdateRequest;
import com.unisence.iot.admin.system.vo.RoleFormOptionsVO;
import com.unisence.iot.admin.system.vo.RoleVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

public interface SysRoleService {

    PageResult<RoleVO> pageRoles(PageRequest<RoleQuery> request);

    RoleVO getRole(Long roleId);

    RoleFormOptionsVO getFormOptions();

    Long createRole(RoleCreateRequest request);

    void updateRole(Long roleId, RoleUpdateRequest request);

    void deleteRole(Long roleId);
}
