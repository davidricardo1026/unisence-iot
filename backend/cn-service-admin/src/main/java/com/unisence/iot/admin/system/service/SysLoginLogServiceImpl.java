package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.SysLoginLog;
import com.unisence.iot.admin.mapper.SysLoginLogMapper;
import com.unisence.iot.admin.system.converter.SysLoginLogConverter;
import com.unisence.iot.admin.system.dto.LoginLogQuery;
import com.unisence.iot.admin.system.vo.LoginLogVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SysLoginLogServiceImpl
    extends BaseServiceImpl<SysLoginLogMapper, SysLoginLog>
    implements SysLoginLogService {

    private final SysLoginLogConverter converter;

    @Override
    public PageResult<LoginLogVO> pageLoginLogs(PageRequest<LoginLogQuery> request) {
        LoginLogQuery q = request.getQuery();
        LambdaQueryWrapper<SysLoginLog> wrapper = Wrappers.lambdaQuery();
        if (q != null) {
            wrapper.like(StringUtils.hasText(q.getUserCode()), SysLoginLog::getUserCode, q.getUserCode())
                .like(StringUtils.hasText(q.getIpaddr()), SysLoginLog::getIpaddr, q.getIpaddr())
                .eq(q.getStatus() != null, SysLoginLog::getStatus, q.getStatus())
                .ge(q.getBeginTime() != null, SysLoginLog::getLoginTime, q.getBeginTime())
                .le(q.getEndTime() != null, SysLoginLog::getLoginTime, q.getEndTime());
        }
        wrapper.orderByDesc(SysLoginLog::getLoginTime);
        Page<SysLoginLog> page = this.page(new Page<>(request.getPageNum(), request.getPageSize()), wrapper);
        List<LoginLogVO> list = page.getRecords().stream().map(converter::toVO).collect(Collectors.toList());
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    public void deleteById(Long loginLogId) {
        this.removeById(loginLogId);
    }

    @Override
    public void batchDelete(List<Long> ids) {
        this.removeByIds(ids);
    }

    @Override
    public void cleanAll() {
        this.remove(Wrappers.emptyWrapper());
    }
}
