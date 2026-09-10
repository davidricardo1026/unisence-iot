package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.SysOperLog;
import com.unisence.iot.admin.mapper.SysOperLogMapper;
import com.unisence.iot.admin.system.converter.SysOperLogConverter;
import com.unisence.iot.admin.system.dto.OperLogQuery;
import com.unisence.iot.admin.system.vo.OperLogVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysOperLogServiceImpl} 单元测试（C 类日志表：只查 + 物理删）。
 * <p>
 * 该服务只用基类 {@code ServiceImpl.baseMapper}（无自有 mapper 字段），故用 {@link ReflectionTestUtils} 注入 mock。
 * 覆盖：分页 VO 装配、detail 存在性护栏（2050）、cleanAll 整表清空（走 {@code remove(wrapper)}→{@code delete}）。
 * 注：{@code batchDelete} 经 {@code removeByIds}→{@code removeById}，MP 内部需 {@code TableInfoHelper}（纯单测无注册会抛
 * MybatisPlusException），与 {@code deleteUser} 等同属集成测试范畴，不在此单测。
 */
class SysOperLogServiceImplTest {

    private SysOperLogMapper operLogMapper;
    private SysOperLogConverter converter;
    private SysOperLogServiceImpl service;

    @BeforeEach
    void setUp() {
        operLogMapper = mock(SysOperLogMapper.class);
        converter = mock(SysOperLogConverter.class);
        service = new SysOperLogServiceImpl(converter);
        ReflectionTestUtils.setField(service, "baseMapper", operLogMapper);
    }

    @Test
    @DisplayName("pageOperLogs：把 mapper 分页结果映射为 OperLogVO 并保留 total")
    void pageOperLogs_mapsRecordsAndTotal() {
        SysOperLog e = new SysOperLog();
        e.setOperLogId(5L);
        e.setTitle("用户管理");
        e.setStatus(1);
        Page<SysOperLog> page = new Page<>(1, 10);
        page.setRecords(List.of(e));
        page.setTotal(1);
        when(operLogMapper.selectPage(any(), any())).thenReturn(page);
        OperLogVO vo = new OperLogVO();
        vo.setOperLogId(5L);
        vo.setTitle("用户管理");
        when(converter.toVO(e)).thenReturn(vo);

        PageResult<OperLogVO> res = service.pageOperLogs(PageRequest.of(1, 10, new OperLogQuery()));

        assertThat(res.total()).isEqualTo(1);
        assertThat(res.list()).containsExactly(vo);
        verify(converter).toVO(e);
    }

    @Test
    @DisplayName("detail：存在 → 返回 VO；不存在 → 404/2050")
    void detail_foundAndNotFound() {
        SysOperLog e = new SysOperLog();
        e.setOperLogId(7L);
        e.setTitle("角色管理");
        OperLogVO vo = new OperLogVO();
        vo.setOperLogId(7L);
        vo.setTitle("角色管理");
        when(operLogMapper.selectById(7L)).thenReturn(e);
        when(converter.toVO(e)).thenReturn(vo);
        assertThat(service.detail(7L).getTitle()).isEqualTo("角色管理");

        when(operLogMapper.selectById(8L)).thenReturn(null);
        assertThatThrownBy(() -> service.detail(8L))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> {
                BusinessException be = (BusinessException) ex;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(be.getCode()).isEqualTo(2050);
            });
    }

    @Test
    @DisplayName("cleanAll：整表清空（委托 mapper.delete(空条件)）")
    void cleanAll_deletesAll() {
        service.cleanAll();

        verify(operLogMapper).delete(any());
    }
}
