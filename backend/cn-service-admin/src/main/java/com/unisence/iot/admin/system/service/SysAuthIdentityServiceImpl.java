package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unisence.iot.admin.entity.SysAuthIdentity;
import com.unisence.iot.admin.mapper.SysAuthIdentityMapper;
import com.unisence.iot.common.service.BaseServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class SysAuthIdentityServiceImpl extends BaseServiceImpl<SysAuthIdentityMapper, SysAuthIdentity>
    implements SysAuthIdentityService {

    @Override
    @Transactional
    public void deleteByUserId(Long userId) {
        // 1. 查出该用户下的所有凭证 ID
        List<Long> ids = this.list(new LambdaQueryWrapper<SysAuthIdentity>()
                                       .eq(SysAuthIdentity::getUserId, userId))
            .stream()
            .map(SysAuthIdentity::getIdentityId)
            .filter(Objects::nonNull)
            .toList();

        // 2. 调用基类的 removeByIds 逐个逻辑删除，实现 deleted = identityId
        this.removeByIds(ids);
    }
}
