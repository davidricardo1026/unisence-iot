package com.unisence.iot.admin.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.unisence.iot.admin.entity.SysDictData;
import com.unisence.iot.admin.entity.SysDictType;
import com.unisence.iot.admin.mapper.SysDictDataMapper;
import com.unisence.iot.admin.mapper.SysDictTypeMapper;
import com.unisence.iot.common.api.dict.DictCatalogVO;
import com.unisence.iot.common.api.dict.DictItemVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DictCatalogServiceImpl implements DictCatalogService {

    private static final DateTimeFormatter VERSION_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SysDictTypeMapper dictTypeMapper;
    private final SysDictDataMapper dictDataMapper;

    @Override
    public DictCatalogVO getCatalog(String sinceVersion) {
        DictCatalogVO catalog = loadFromDb();
        if (sinceVersion != null && sinceVersion.equals(catalog.version())) {
            return null;
        }
        return catalog;
    }

    private DictCatalogVO loadFromDb() {
        List<SysDictType> types = dictTypeMapper.selectList(
            new LambdaQueryWrapper<SysDictType>().eq(SysDictType::getStatus, 1));
        List<String> enabledTypes = types.stream().map(SysDictType::getDictType).toList();

        List<SysDictData> dataList = enabledTypes.isEmpty()
            ? List.of()
            : dictDataMapper.selectList(new LambdaQueryWrapper<SysDictData>()
                                            .in(SysDictData::getDictType, enabledTypes)
                                            .eq(SysDictData::getStatus, 1));

        Map<String, List<DictItemVO>> grouped = dataList.stream()
            .collect(Collectors.groupingBy(
                SysDictData::getDictType,
                Collectors.mapping(d -> new DictItemVO(
                    d.getDictValue(),
                    d.getDictLabel(),
                    d.getSortOrder(),
                    null
                ), Collectors.toList())
            ));

        grouped.values().forEach(list -> list.sort(Comparator.comparing(
            item -> item.sort() != null ? item.sort() : 0)));

        LocalDateTime maxTypeTime = types.stream()
            .map(SysDictType::getUpdateTime)
            .filter(t -> t != null)
            .max(LocalDateTime::compareTo)
            .orElse(LocalDateTime.now());
        LocalDateTime maxDataTime = dataList.stream()
            .map(SysDictData::getUpdateTime)
            .filter(t -> t != null)
            .max(LocalDateTime::compareTo)
            .orElse(maxTypeTime);
        LocalDateTime versionTime = maxTypeTime.isAfter(maxDataTime) ? maxTypeTime : maxDataTime;

        return new DictCatalogVO(versionTime.format(VERSION_FMT), grouped);
    }
}
