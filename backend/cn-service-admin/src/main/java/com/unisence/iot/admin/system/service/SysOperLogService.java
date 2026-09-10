package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.unisence.iot.admin.entity.SysOperLog;
import com.unisence.iot.admin.system.dto.OperLogQuery;
import com.unisence.iot.admin.system.vo.OperLogVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

import java.util.List;

public interface SysOperLogService extends IService<SysOperLog> {
    PageResult<OperLogVO> pageOperLogs(PageRequest<OperLogQuery> request);

    OperLogVO detail(Long operLogId);

    void batchDelete(List<Long> ids);

    void cleanAll();
}
