package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.entity.SysConfig;
import com.unisence.iot.admin.mapper.SysConfigMapper;
import com.unisence.iot.admin.system.converter.SysConfigConverter;
import com.unisence.iot.admin.system.dto.ConfigCreateRequest;
import com.unisence.iot.admin.system.dto.ConfigQuery;
import com.unisence.iot.admin.system.dto.ConfigUpdateRequest;
import com.unisence.iot.admin.system.vo.ConfigVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysConfigServiceImpl extends BaseServiceImpl<SysConfigMapper, SysConfig> implements SysConfigService {

    private final SysConfigMapper configMapper;
    private final SysConfigConverter configConverter;

    @Override
    public PageResult<ConfigVO> pageConfigs(PageRequest<ConfigQuery> request) {
        ConfigQuery query = request.getQuery();
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        if (query != null) {
            wrapper.like(StringUtils.hasText(query.getConfigName()), SysConfig::getConfigName, query.getConfigName())
                .like(StringUtils.hasText(query.getConfigKey()), SysConfig::getConfigKey, query.getConfigKey())
                .eq(query.getConfigType() != null, SysConfig::getConfigType, query.getConfigType());
        }
        wrapper.orderByDesc(SysConfig::getCreateTime);

        Page<SysConfig> page = configMapper.selectPage(
            new Page<>(request.getPageNum(), request.getPageSize()),
            wrapper);
        List<ConfigVO> list = page.getRecords().stream().map(configConverter::toVO).toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    @Transactional
    public Long createConfig(ConfigCreateRequest request) {
        assertConfigKeyUnique(request.getConfigKey(), null);
        SysConfig config = configConverter.toEntity(request);
        configMapper.insert(config);
        return config.getConfigId();
    }

    @Override
    @Transactional
    public void updateConfig(Long configId, ConfigUpdateRequest request) {
        SysConfig config = requireConfig(configId);
        config.setVersion(request.getVersion());
        configConverter.updateEntity(config, request);
        configMapper.updateByIdWithVersionCheck(config);
    }

    @Override
    @Transactional
    public void deleteConfig(Long configId) {
        SysConfig config = requireConfig(configId);
        if (config.getConfigType() != null && config.getConfigType() == 0) { // 0 为系统参数
            throw new BusinessException(HttpStatus.FORBIDDEN, 2024, "系统内置参数不允许删除");
        }
        // 👑 铁律执行
        this.removeById(configId);
    }

    @Override
    public void clearCache() {
        // 目前作为占位，后续对齐 CacheRegistry 进行清理
    }

    private void assertConfigKeyUnique(String configKey, Long excludeId) {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<SysConfig>()
            .eq(SysConfig::getConfigKey, configKey);
        if (excludeId != null) {
            wrapper.ne(SysConfig::getConfigId, excludeId);
        }
        Long count = configMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 2025, "参数键名已存在");
        }
    }

    private SysConfig requireConfig(Long configId) {
        SysConfig config = configMapper.selectById(configId);
        if (config == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 2026, "参数不存在");
        }
        return config;
    }
}
