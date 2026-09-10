package com.unisence.iot.admin.system.service;

import com.unisence.iot.admin.cache.CacheEvictService;
import com.unisence.iot.admin.entity.SysDept;
import com.unisence.iot.admin.mapper.SysDeptMapper;
import com.unisence.iot.admin.mapper.SysUserMapper;
import com.unisence.iot.admin.system.converter.SysDeptConverter;
import com.unisence.iot.admin.system.dto.DeptCreateRequest;
import com.unisence.iot.admin.system.dto.DeptUpdateRequest;
import com.unisence.iot.admin.system.vo.DeptTreeVO;
import com.unisence.iot.common.exception.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysDeptServiceImpl} 业务逻辑单元测试。
 * <p>
 * 4 个依赖，纯 Mockito。覆盖：树装配、{@code listDescendantIds} 递归、ancestors 路径构建、
 * 父部门校验、禁止移动到子孙（环检查）、删除前子部门/用户占用检查。
 * <p>
 * {@code deleteDept} 落库经 {@code BaseServiceImpl.removeById}（依赖 MyBatis 表元信息），
 * 只测其三道前置护栏。
 */
class SysDeptServiceImplTest {

    private SysDeptMapper deptMapper;
    private SysUserMapper userMapper;
    private CacheEvictService cacheEvictService;
    private SysDeptConverter deptConverter;
    private SysDeptServiceImpl service;

    @BeforeEach
    void setUp() {
        deptMapper = mock(SysDeptMapper.class);
        userMapper = mock(SysUserMapper.class);
        cacheEvictService = mock(CacheEvictService.class);
        deptConverter = mock(SysDeptConverter.class);
        service = new SysDeptServiceImpl(deptMapper, userMapper, cacheEvictService, deptConverter);
    }

    // ---------- getDeptTree ----------

    @Test
    @DisplayName("getDeptTree：扁平列表按 parentId 装配成树")
    void getDeptTree_buildsNestedTree() {
        when(deptMapper.selectList(any())).thenReturn(List.of(
            dept(1L, 0L), dept(2L, 1L), dept(3L, 1L), dept(4L, 0L)));
        stubToTreeNode();

        List<DeptTreeVO> tree = service.getDeptTree(null, null);

        assertThat(tree).extracting(DeptTreeVO::getDeptId).containsExactly(1L, 4L);
        assertThat(tree.get(0).getChildren()).extracting(DeptTreeVO::getDeptId).containsExactly(2L, 3L);
    }

    // ---------- listDescendantIds ----------

    @Test
    @DisplayName("listDescendantIds：deptId 为空 → 空列表，不查库")
    void listDescendantIds_null_returnsEmpty() {
        assertThat(service.listDescendantIds(null)).isEmpty();
        verify(deptMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("listDescendantIds：递归收集自身 + 所有子孙")
    void listDescendantIds_collectsSelfAndDescendants() {
        // 1 -> {2,3}; 2 -> {4}; 5 独立
        when(deptMapper.selectList(any())).thenReturn(List.of(
            dept(1L, 0L), dept(2L, 1L), dept(3L, 1L), dept(4L, 2L), dept(5L, 0L)));

        assertThat(service.listDescendantIds(1L)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
    }

    // ---------- createDept ----------

    @Test
    @DisplayName("createDept：根部门 → ancestors=\"0\"，不查父")
    void createDept_root_ancestorsZero() {
        SysDept entity = new SysDept();
        when(deptConverter.toEntity(any())).thenReturn(entity);
        when(deptMapper.insert(any(SysDept.class))).thenAnswer(inv -> {
            ((SysDept) inv.getArgument(0)).setDeptId(30L);
            return 1;
        });

        Long id = service.createDept(createRequest(0L, "研发"));

        assertThat(id).isEqualTo(30L);
        assertThat(entity.getAncestors()).isEqualTo("0");
        verify(deptMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("createDept：有父部门 → ancestors 由父路径拼接")
    void createDept_withParent_buildsAncestors() {
        SysDept parent = dept(1L, 0L); // ancestors="0"
        when(deptMapper.selectById(1L)).thenReturn(parent);
        SysDept entity = new SysDept();
        when(deptConverter.toEntity(any())).thenReturn(entity);
        when(deptMapper.insert(any(SysDept.class))).thenAnswer(inv -> {
            ((SysDept) inv.getArgument(0)).setDeptId(31L);
            return 1;
        });

        Long id = service.createDept(createRequest(1L, "小组"));

        assertThat(id).isEqualTo(31L);
        assertThat(entity.getAncestors()).isEqualTo("0,1");
    }

    @Test
    @DisplayName("createDept：父部门不存在 → 400/2017，不插入")
    void createDept_parentNotFound_badRequest() {
        when(deptMapper.selectById(99L)).thenReturn(null);

        assertBusinessError(() -> service.createDept(createRequest(99L, "x")),
                            HttpStatus.BAD_REQUEST, 2017);

        verify(deptMapper, never()).insert(any(SysDept.class));
    }

    // ---------- updateDept ----------

    @Test
    @DisplayName("updateDept：部门不存在 → 404/2018")
    void updateDept_notFound_notFound() {
        when(deptMapper.selectById(7L)).thenReturn(null);

        assertBusinessError(() -> service.updateDept(7L, updateRequest(0L)),
                            HttpStatus.NOT_FOUND, 2018);
    }

    @Test
    @DisplayName("updateDept：父部门指向自身 → 400/2016，不落库")
    void updateDept_selfParent_badRequest() {
        when(deptMapper.selectById(7L)).thenReturn(dept(7L, 0L));

        assertBusinessError(() -> service.updateDept(7L, updateRequest(7L)),
                            HttpStatus.BAD_REQUEST, 2016);

        verify(deptMapper, never()).updateByIdWithVersionCheck(any());
    }

    @Test
    @DisplayName("updateDept：移动到自己的子孙下 → 400/2016，不落库")
    void updateDept_moveToDescendant_badRequest() {
        when(deptMapper.selectById(7L)).thenReturn(dept(7L, 0L));
        when(deptMapper.selectById(10L)).thenReturn(dept(10L, 7L)); // 目标父存在
        when(deptMapper.selectList(any())).thenReturn(List.of(dept(7L, 0L), dept(10L, 7L)));

        assertBusinessError(() -> service.updateDept(7L, updateRequest(10L)),
                            HttpStatus.BAD_REQUEST, 2016);

        verify(deptMapper, never()).updateByIdWithVersionCheck(any());
    }

    @Test
    @DisplayName("updateDept：父级未变 → 落库、失效缓存，不刷新子孙 ancestors")
    void updateDept_noParentChange_updatesAndEvicts() {
        SysDept dept7 = dept(7L, 0L);
        when(deptMapper.selectById(7L)).thenReturn(dept7);

        DeptUpdateRequest req = updateRequest(0L); // 与现有 parentId 相同
        req.setVersion(3);
        service.updateDept(7L, req);

        assertThat(dept7.getVersion()).isEqualTo(3);
        verify(deptConverter).updateEntity(dept7, req);
        verify(deptMapper).updateByIdWithVersionCheck(dept7);
        verify(cacheEvictService).scheduleEvict(any(), any(), any());
        verify(deptMapper, never()).updateById(any(SysDept.class)); // 未变父 → 不 refreshChildAncestors
    }

    // ---------- deleteDept（仅护栏）----------

    @Test
    @DisplayName("deleteDept：部门不存在 → 404/2018")
    void deleteDept_notFound_notFound() {
        when(deptMapper.selectById(9L)).thenReturn(null);

        assertBusinessError(() -> service.deleteDept(9L), HttpStatus.NOT_FOUND, 2018);
    }

    @Test
    @DisplayName("deleteDept：存在子部门 → 409/2014，不检查用户")
    void deleteDept_hasChildren_conflict() {
        when(deptMapper.selectById(9L)).thenReturn(dept(9L, 0L));
        when(deptMapper.selectCount(any())).thenReturn(1L);

        assertBusinessError(() -> service.deleteDept(9L), HttpStatus.CONFLICT, 2014);

        verify(userMapper, never()).selectCount(any());
    }

    @Test
    @DisplayName("deleteDept：部门下存在用户 → 409/2015")
    void deleteDept_hasUsers_conflict() {
        when(deptMapper.selectById(9L)).thenReturn(dept(9L, 0L));
        when(deptMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.selectCount(any())).thenReturn(3L);

        assertBusinessError(() -> service.deleteDept(9L), HttpStatus.CONFLICT, 2015);
    }

    // ---------- helpers ----------

    private void stubToTreeNode() {
        when(deptConverter.toTreeNode(any())).thenAnswer(inv -> {
            SysDept d = inv.getArgument(0);
            DeptTreeVO vo = new DeptTreeVO();
            vo.setDeptId(d.getDeptId());
            vo.setParentId(d.getParentId());
            return vo;
        });
    }

    private static SysDept dept(Long deptId, Long parentId) {
        SysDept d = new SysDept();
        d.setDeptId(deptId);
        d.setParentId(parentId);
        d.setAncestors("0");
        d.setDeptName("dept" + deptId);
        d.setStatus(1);
        return d;
    }

    private static DeptCreateRequest createRequest(Long parentId, String name) {
        DeptCreateRequest r = new DeptCreateRequest();
        r.setParentId(parentId);
        r.setDeptName(name);
        r.setStatus(1);
        return r;
    }

    private static DeptUpdateRequest updateRequest(Long parentId) {
        DeptUpdateRequest r = new DeptUpdateRequest();
        r.setParentId(parentId);
        r.setDeptName("研发");
        r.setStatus(1);
        r.setVersion(0);
        return r;
    }

    private static void assertBusinessError(ThrowingCallable call, HttpStatus status, int code) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.getStatus()).isEqualTo(status);
                assertThat(ex.getCode()).isEqualTo(code);
            });
    }
}
