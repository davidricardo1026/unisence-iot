package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.system.dto.DeptCreateRequest;
import com.unisence.iot.admin.system.dto.DeptUpdateRequest;
import com.unisence.iot.admin.system.vo.DeptTreeVO;

import java.util.List;

public interface SysDeptService {

    List<DeptTreeVO> getDeptTree(String deptName, Integer status);

    List<Long> listDescendantIds(Long deptId);

    Long createDept(DeptCreateRequest request);

    void updateDept(Long deptId, DeptUpdateRequest request);

    void deleteDept(Long deptId);
}
