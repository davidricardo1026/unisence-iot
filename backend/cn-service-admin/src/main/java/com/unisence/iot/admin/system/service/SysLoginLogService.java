package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.unisence.iot.admin.entity.SysLoginLog;
import com.unisence.iot.admin.system.dto.LoginLogQuery;
import com.unisence.iot.admin.system.vo.LoginLogVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

import java.util.List;

public interface SysLoginLogService extends IService<SysLoginLog> {
    PageResult<LoginLogVO> pageLoginLogs(PageRequest<LoginLogQuery> request);

    void deleteById(Long loginLogId);

    void batchDelete(List<Long> ids);

    void cleanAll();
}
