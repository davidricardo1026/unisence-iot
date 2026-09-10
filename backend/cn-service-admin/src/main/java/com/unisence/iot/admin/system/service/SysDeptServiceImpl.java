package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unisence.iot.admin.cache.CacheEvictService;
import com.unisence.iot.admin.entity.SysDept;
import com.unisence.iot.admin.entity.SysUser;
import com.unisence.iot.admin.mapper.SysDeptMapper;
import com.unisence.iot.admin.mapper.SysUserMapper;
import com.unisence.iot.admin.system.converter.SysDeptConverter;
import com.unisence.iot.admin.system.dto.DeptCreateRequest;
import com.unisence.iot.admin.system.dto.DeptUpdateRequest;
import com.unisence.iot.admin.system.vo.DeptTreeVO;
import com.unisence.iot.common.cache.CacheDomain;
import com.unisence.iot.common.cache.ClearScope;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysDeptServiceImpl extends BaseServiceImpl<SysDeptMapper, SysDept> implements SysDeptService {

    private final SysDeptMapper deptMapper;
    private final SysUserMapper userMapper;
    private final CacheEvictService cacheEvictService;
    private final SysDeptConverter deptConverter;

    @Override
    public List<DeptTreeVO> getDeptTree(String deptName, Integer status) {
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(deptName), SysDept::getDeptName, deptName)
            .eq(status != null, SysDept::getStatus, status)
            .orderByAsc(SysDept::getSortOrder)
            .orderByAsc(SysDept::getDeptId);
        List<SysDept> depts = deptMapper.selectList(wrapper);
        Map<Long, DeptTreeVO> nodeMap = depts.stream().collect(Collectors.toMap(
            SysDept::getDeptId,
            deptConverter::toTreeNode
        ));
        List<DeptTreeVO> roots = new ArrayList<>();
        for (SysDept dept : depts) {
            DeptTreeVO node = nodeMap.get(dept.getDeptId());
            if (dept.getParentId() == null || dept.getParentId() == 0L) {
                roots.add(node);
            } else {
                DeptTreeVO parent = nodeMap.get(dept.getParentId());
                if (parent != null) {
                    parent.getChildren().add(node);
                } else {
                    roots.add(node);
                }
            }
        }
        return roots;
    }

    @Override
    public List<Long> listDescendantIds(Long deptId) {
        if (deptId == null) {
            return List.of();
        }
        List<SysDept> all = deptMapper.selectList(null);
        Map<Long, List<Long>> childrenMap = all.stream()
            .collect(Collectors.groupingBy(
                d -> d.getParentId() == null ? 0L : d.getParentId(),
                Collectors.mapping(SysDept::getDeptId, Collectors.toList())
            ));
        Set<Long> ids = new HashSet<>();
        collectDescendants(deptId, childrenMap, ids);
        return new ArrayList<>(ids);
    }

    @Override
    @Transactional
    public Long createDept(DeptCreateRequest request) {
        validateParent(request.getParentId(), null);
        String ancestors = buildAncestors(request.getParentId());

        SysDept dept = deptConverter.toEntity(request);
        dept.setAncestors(ancestors);
        deptMapper.insert(dept);

        return dept.getDeptId();
    }

    @Override
    @Transactional
    public void updateDept(Long deptId, DeptUpdateRequest request) {
        SysDept dept = requireDept(deptId);
        dept.setVersion(request.getVersion());
        validateParent(request.getParentId(), deptId);
        assertNotMoveToDescendant(deptId, request.getParentId());

        boolean parentChanged = !dept.getParentId().equals(request.getParentId());
        deptConverter.updateEntity(dept, request);

        if (parentChanged) {
            dept.setAncestors(buildAncestors(request.getParentId()));
            deptMapper.updateByIdWithVersionCheck(dept);
            refreshChildAncestors(deptId, dept.getAncestors());
        } else {
            deptMapper.updateByIdWithVersionCheck(dept);
        }

        cacheEvictService.scheduleEvict(CacheDomain.DEPT, ClearScope.ENTRY, String.valueOf(deptId));
    }

    @Override
    @Transactional
    public void deleteDept(Long deptId) {
        requireDept(deptId);
        Long childCount = deptMapper.selectCount(
            new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, deptId));
        if (childCount != null && childCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2014, "存在子部门，无法删除");
        }
        Long userCount = userMapper.selectCount(
            new LambdaQueryWrapper<SysUser>().eq(SysUser::getDeptId, deptId));
        if (userCount != null && userCount > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2015, "部门下存在用户，无法删除");
        }
        // 👑 铁律执行
        this.removeById(deptId);
        cacheEvictService.scheduleEvict(CacheDomain.DEPT, ClearScope.ENTRY, String.valueOf(deptId));
    }

    private void assertNotMoveToDescendant(Long deptId, Long newParentId) {
        if (newParentId == null || newParentId == 0L) {
            return;
        }
        List<Long> descendants = listDescendantIds(deptId);
        if (descendants.contains(newParentId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2016, "不能将部门移动到其子部门下");
        }
    }

    private void refreshChildAncestors(Long deptId, String parentAncestors) {
        String currentPrefix = parentAncestors + "," + deptId;
        List<SysDept> children = deptMapper.selectList(
            new LambdaQueryWrapper<SysDept>().eq(SysDept::getParentId, deptId));
        for (SysDept child : children) {
            child.setAncestors(currentPrefix);
            deptMapper.updateById(child);
            refreshChildAncestors(child.getDeptId(), currentPrefix);
        }
    }

    private String buildAncestors(Long parentId) {
        if (parentId == null || parentId == 0L) {
            return "0";
        }
        SysDept parent = requireDept(parentId);
        return parent.getAncestors() + "," + parentId;
    }

    private void validateParent(Long parentId, Long selfDeptId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (selfDeptId != null && parentId.equals(selfDeptId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2016, "父部门不能是自身");
        }
        SysDept parent = deptMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 2017, "父部门不存在");
        }
    }

    private SysDept requireDept(Long deptId) {
        SysDept dept = deptMapper.selectById(deptId);
        if (dept == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 2018, "部门不存在");
        }
        return dept;
    }

    private void collectDescendants(Long deptId, Map<Long, List<Long>> childrenMap, Set<Long> ids) {
        ids.add(deptId);
        for (Long childId : childrenMap.getOrDefault(deptId, List.of())) {
            collectDescendants(childId, childrenMap, ids);
        }
    }
}
