package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.SysLoginLog;
import com.unisence.iot.admin.mapper.SysLoginLogMapper;
import com.unisence.iot.admin.system.converter.SysLoginLogConverter;
import com.unisence.iot.admin.system.dto.LoginLogQuery;
import com.unisence.iot.admin.system.vo.LoginLogVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SysLoginLogServiceImpl} 单元测试（C 类日志表：只查 + 物理删）。
 * 覆盖：分页经 converter 装配 VO 并保留 total；cleanAll 整表清空。
 * baseMapper 用 {@link ReflectionTestUtils} 注入（该服务仅用基类 mapper + 自有 converter）。
 * 注：{@code deleteById}/{@code batchDelete} 经 {@code removeById}，MP 内部需 {@code TableInfoHelper}，纯单测测不了 → 集成范畴。
 */
class SysLoginLogServiceImplTest {

    private SysLoginLogMapper loginLogMapper;
    private SysLoginLogConverter converter;
    private SysLoginLogServiceImpl service;

    @BeforeEach
    void setUp() {
        loginLogMapper = mock(SysLoginLogMapper.class);
        converter = mock(SysLoginLogConverter.class);
        service = new SysLoginLogServiceImpl(converter);
        ReflectionTestUtils.setField(service, "baseMapper", loginLogMapper);
    }

    @Test
    @DisplayName("pageLoginLogs：mapper 分页 → converter 装配 VO，total 透传")
    void pageLoginLogs_mapsViaConverter() {
        SysLoginLog e = new SysLoginLog();
        e.setLoginLogId(3L);
        e.setUserCode("admin");
        Page<SysLoginLog> page = new Page<>(1, 10);
        page.setRecords(List.of(e));
        page.setTotal(1);
        when(loginLogMapper.selectPage(any(), any())).thenReturn(page);
        LoginLogVO vo = new LoginLogVO();
        vo.setLoginLogId(3L);
        vo.setUserCode("admin");
        when(converter.toVO(e)).thenReturn(vo);

        PageResult<LoginLogVO> res = service.pageLoginLogs(PageRequest.of(1, 10, new LoginLogQuery()));

        assertThat(res.total()).isEqualTo(1);
        assertThat(res.list()).containsExactly(vo);
        verify(converter).toVO(e);
    }

    @Test
    @DisplayName("cleanAll：整表清空")
    void cleanAll_deletesAll() {
        service.cleanAll();
        verify(loginLogMapper).delete(any());
    }
}
