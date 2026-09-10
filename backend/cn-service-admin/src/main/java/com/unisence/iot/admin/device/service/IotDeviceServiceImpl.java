package com.unisence.iot.admin.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.config.AdminTimeSeriesProperties;
import com.unisence.iot.admin.device.converter.IotDeviceConverter;
import com.unisence.iot.admin.device.dto.*;
import com.unisence.iot.admin.device.support.*;
import com.unisence.iot.admin.device.vo.*;
import com.unisence.iot.admin.entity.*;
import com.unisence.iot.admin.mapper.*;
import com.unisence.iot.admin.metadata.MetadataCommit;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.service.BaseServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IotDeviceServiceImpl extends BaseServiceImpl<IotDeviceMapper, IotDevice> implements IotDeviceService {

    private final IotDeviceMapper deviceMapper;
    private final IotProductMapper productMapper;
    private final IotTmPropertyMapper propertyMapper;
    private final IotTmEventMapper eventMapper;
    private final IotDeviceFormIndexMapper formIndexMapper;
    private final IotDeviceConverter deviceConverter;
    private final JsonMaps jsonMaps;
    private final DeviceFormSupport formSupport;
    private final MetadataCommit metadataCommit;
    private final LatestPropertyReader latestPropertyReader;
    private final PropertyHistoryReader propertyHistoryReader;
    private final PropertyRawValueReader propertyRawValueReader;
    private final DeviceEventReader deviceEventReader;
    private final DeviceOnlineLogReader deviceOnlineLogReader;
    private final AdminTimeSeriesProperties timeSeriesProperties;

    @Override
    public PageResult<DeviceVO> pageDevices(PageRequest<DeviceQuery> request) {
        DeviceQuery query = request.getQuery();
        if (query == null) {
            query = new DeviceQuery();
        }
        IotProduct product = query.getProductId() == null ? null : requireProduct(query.getProductId());
        if (product == null && query.getFormFilters() != null && !query.getFormFilters().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3009, "动态筛选必须指定 productId");
        }
        Map<String, DeviceFormSupport.FieldMeta> filterFields = product == null ? Map.of()
            : formSupport.assertFormFilters(product.getDeviceFormSchema(), query.getFormFilters());

        Set<Long> filterIds = product == null ? null
            : resolveFormFilterDeviceIds(query.getProductId(), query.getFormFilters(), filterFields);
        if (filterIds != null && filterIds.isEmpty()) {
            return new PageResult<>(List.of(), 0);
        }

        LambdaQueryWrapper<IotDevice> wrapper = new LambdaQueryWrapper<IotDevice>()
            .eq(query.getProductId() != null, IotDevice::getProductId, query.getProductId())
            .like(StringUtils.hasText(query.getDeviceCode()), IotDevice::getDeviceCode, query.getDeviceCode())
            .like(StringUtils.hasText(query.getDeviceName()), IotDevice::getDeviceName, query.getDeviceName())
            .eq(query.getStatus() != null, IotDevice::getStatus, query.getStatus());
        if (filterIds != null) {
            wrapper.in(IotDevice::getDeviceId, filterIds);
        }
        wrapper.orderByDesc(IotDevice::getCreateTime);

        Page<IotDevice> page = deviceMapper.selectPage(new Page<>(request.getPageNum(), request.getPageSize()),
                                                       wrapper);
        Map<Long, IotProduct> productsById;
        if (product != null) {
            productsById = Map.of(product.getProductId(), product);
        } else if (page.getRecords().isEmpty()) {
            productsById = Map.of();
        } else {
            productsById = productMapper.selectBatchIds(page.getRecords().stream().map(IotDevice::getProductId).toList())
                .stream().collect(Collectors.toMap(IotProduct::getProductId, Function.identity()));
        }
        List<DeviceVO> list = page.getRecords().stream().map(d -> {
            IotProduct deviceProduct = productsById.get(d.getProductId());
            return toVo(d, deviceProduct, false);
        }).toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    public DeviceVO getDevice(Long deviceId) {
        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        return toVo(device, product, true);
    }

    @Override
    public LatestPropertySnapshotVO getLatestProperties(Long deviceId) {
        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        return latestPropertyReader.read(product.getProductKey(), device.getDeviceCode());
    }

    @Override
    public PropertyHistoryVO getPropertyHistory(Long deviceId, String identifier, long from, long to) {
        if (!StringUtils.hasText(identifier) || from < 0 || to <= from
            || to - from > timeSeriesProperties.getHistoryMaxRangeMs()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3017, "属性曲线时间范围非法");
        }
        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        IotTmProperty property = requireProperty(device.getProductId(), identifier);
        if (!Set.of("int", "float", "double", "bool").contains(property.getDataType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                                        3017,
                                        "该属性类型不支持曲线: " + property.getDataType());
        }
        return propertyHistoryReader.read(product.getProductKey(), device.getDeviceCode(),
                                          property.getIdentifier(), property.getDataType(), property.getRetentionDays(),
                                          property.getUnit(),
                                          from, to);
    }

    @Override
    public PageResult<PropertyRawValueVO> pagePropertyRawValues(Long deviceId,
                                                                String identifier,
                                                                PageRequest<PropertyRawQuery> request) {
        PropertyRawQuery query = request.getQuery();
        int pageNum = request.getPageNum();
        int pageSize = request.getPageSize();
        if (!StringUtils.hasText(identifier) || query == null || query.getFrom() == null || query.getTo() == null
            || query.getFrom() < 0 || query.getTo() <= query.getFrom()
            || query.getTo() - query.getFrom() > timeSeriesProperties.getHistoryMaxRangeMs()
            || pageNum < 1 || pageSize < 1 || pageSize > timeSeriesProperties.getPropertyRawMaxPageSize()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3017, "属性原始值查询参数非法");
        }
        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        IotTmProperty property = requireProperty(device.getProductId(), identifier);
        return propertyRawValueReader.read(product.getProductKey(), device.getDeviceCode(), property.getIdentifier(),
                                           property.getDataType(), property.getRetentionDays(),
                                           query.getFrom(), query.getTo(), pageNum, pageSize);
    }

    @Override
    public PageResult<DeviceEventVO> pageDeviceEvents(Long deviceId, PageRequest<DeviceEventQuery> request) {
        DeviceEventQuery query = request.getQuery();
        int pageNum = request.getPageNum();
        int pageSize = request.getPageSize();
        if (query == null || query.getFrom() == null || query.getTo() == null
            || query.getFrom() < 0 || query.getTo() <= query.getFrom()
            || query.getTo() - query.getFrom() > timeSeriesProperties.getEventQueryMaxRangeMs()
            || pageNum < 1 || pageSize < 1 || pageSize > timeSeriesProperties.getEventQueryMaxPageSize()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3017, "设备事件查询参数非法");
        }

        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        List<IotTmEvent> definitions = eventMapper.selectList(
            new LambdaQueryWrapper<IotTmEvent>().eq(IotTmEvent::getProductId, device.getProductId()));
        Map<String, String> eventNames = definitions.stream().collect(Collectors.toMap(
            IotTmEvent::getIdentifier, IotTmEvent::getEventName));
        String identifier = query.getIdentifier() == null ? "" : query.getIdentifier().trim().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(identifier)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3017, "设备事件查询必须指定事件标识符");
        }
        if (!eventNames.containsKey(identifier)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3020, "设备所属产品不存在事件: " + identifier);
        }
        return deviceEventReader.read(product.getProductKey(), device.getDeviceCode(), identifier,
                                      query.getFrom(), query.getTo(), pageNum, pageSize, eventNames);
    }

    @Override
    public PageResult<DeviceOnlineLogVO> pageDeviceOnlineLogs(Long deviceId,
                                                              PageRequest<DeviceOnlineLogQuery> request) {
        DeviceOnlineLogQuery query = request.getQuery();
        int pageNum = request.getPageNum();
        int pageSize = request.getPageSize();
        if (query == null || query.getFrom() == null || query.getTo() == null
            || query.getFrom() < 0 || query.getTo() <= query.getFrom()
            || query.getTo() - query.getFrom() > timeSeriesProperties.getOnlineLogQueryMaxRangeMs()
            || pageNum < 1 || pageSize < 1 || pageSize > timeSeriesProperties.getOnlineLogQueryMaxPageSize()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3017, "设备上下线历史查询参数非法");
        }
        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        return deviceOnlineLogReader.read(product.getProductKey(), device.getDeviceCode(),
                                          query.getFrom(), query.getTo(), pageNum, pageSize);
    }

    @Override
    public DeviceOnlineHistoryVO getDeviceOnlineHistory(Long deviceId, long from, long to) {
        if (from < 0 || to <= from || to - from > timeSeriesProperties.getOnlineLogQueryMaxRangeMs()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3017, "设备在线状态曲线时间范围非法");
        }
        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        return deviceOnlineLogReader.readHistory(product.getProductKey(), device.getDeviceCode(), from, to,
                                                 timeSeriesProperties.getOnlineLogChartMaxPoints());
    }

    @Override
    @Transactional
    public DeviceCreateVO createDevice(DeviceSaveRequest request) {
        IotProduct product = requireProduct(request.getProductId());
        if (!Objects.equals(product.getProductType(), 1)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3003, "不可将设备挂到标准产品");
        }
        String code = request.getDeviceCode().trim();
        assertCodeUnique(product.getProductId(), code, null);
        IotDevice device = deviceConverter.toEntity(request);
        device.setDeviceCode(code);
        applyGatewayRule(device, request.getGatewayId(), request.getNodeType());
        device.setStatus(0);

        Map<String, Object> stored = formSupport.mergeAndEncrypt(
            product.getDeviceFormSchema(), request.getDeviceFormData(), Map.of());
        device.setDeviceFormData(jsonMaps.write(stored));
        deviceMapper.insert(device);
        rebuildIndex(device, product, stored);
        metadataCommit.commit(MetaKeyEnum.IOT_DEVICE, device.getDeviceId());

        DeviceCreateVO vo = deviceConverter.toCreateVO(device);
        vo.setDeviceFormData(formSupport.toDisplayFormData(product.getDeviceFormSchema(),
                                                           device.getDeviceFormData(),
                                                           true));
        return vo;
    }

    @Override
    @Transactional
    public void updateDevice(Long deviceId, DeviceSaveRequest request) {
        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        device.setVersion(request.getVersion());
        deviceConverter.updateEntity(device, request);
        applyGatewayRule(device, request.getGatewayId(), device.getNodeType());

        Map<String, Object> old = jsonMaps.readMap(device.getDeviceFormData());
        Map<String, Object> stored = formSupport.mergeAndEncrypt(
            product.getDeviceFormSchema(), request.getDeviceFormData(), old);
        device.setDeviceFormData(jsonMaps.write(stored));
        deviceMapper.updateByIdWithVersionCheck(device);
        rebuildIndex(device, product, stored);
        // 静态字段变更已由乐观锁把 us_iot_device.version 加一，它就是设备投影的代际：
        // engine 拿到新版本后，旧 L1/L2 条目立刻因 deviceRowVersion 不匹配失效
        metadataCommit.commit(MetaKeyEnum.IOT_DEVICE, deviceId);
    }

    @Override
    @Transactional
    public void deleteDevice(Long deviceId) {
        IotDevice device = require(deviceId);
        IotProduct product = requireProduct(device.getProductId());
        formIndexMapper.delete(new LambdaQueryWrapper<IotDeviceFormIndex>().eq(IotDeviceFormIndex::getDeviceId,
                                                                               deviceId));
        removeById(device.getDeviceId());
        metadataCommit.commit(MetaKeyEnum.IOT_DEVICE, deviceId);
        // 设备删除不再需要清理最新值缓存：当前值由属性类型×保留档位时序表派生，
        // 随 TTL 自然过期（latest-property-runtime.md §六）
    }

    private Set<Long> resolveFormFilterDeviceIds(Long productId,
                                                 Map<String, DeviceFormFilter> filters,
                                                 Map<String, DeviceFormSupport.FieldMeta> fields) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        Set<Long> result = null;
        for (Map.Entry<String, DeviceFormFilter> e : filters.entrySet()) {
            DeviceFormFilter filter = e.getValue();
            DeviceFormSupport.FieldMeta field = fields.get(e.getKey());
            LambdaQueryWrapper<IotDeviceFormIndex> indexQuery = new LambdaQueryWrapper<IotDeviceFormIndex>()
                .eq(IotDeviceFormIndex::getProductId, productId)
                .eq(IotDeviceFormIndex::getFieldKey, e.getKey());
            if (Set.of("int", "float").contains(field.type)) {
                switch (filter.getOperator()) {
                    case "GT" -> indexQuery.gt(IotDeviceFormIndex::getValueDecimal, filter.getNumber());
                    case "GE" -> indexQuery.ge(IotDeviceFormIndex::getValueDecimal, filter.getNumber());
                    case "EQ" -> indexQuery.eq(IotDeviceFormIndex::getValueDecimal, filter.getNumber());
                    case "NE" -> indexQuery.ne(IotDeviceFormIndex::getValueDecimal, filter.getNumber());
                    case "LE" -> indexQuery.le(IotDeviceFormIndex::getValueDecimal, filter.getNumber());
                    case "LT" -> indexQuery.lt(IotDeviceFormIndex::getValueDecimal, filter.getNumber());
                    default -> throw new BusinessException(HttpStatus.BAD_REQUEST, 3009, "非法数值比较符");
                }
            } else if (Set.of("bool", "enum").contains(field.type)) {
                if ("bool".equals(field.type)) {
                    indexQuery.eq(IotDeviceFormIndex::getValueBoolean, Boolean.valueOf(filter.getValue()));
                } else {
                    indexQuery.eq(IotDeviceFormIndex::getValueText, filter.getValue());
                }
            } else {
                indexQuery.like(IotDeviceFormIndex::getValueText, filter.getValue());
            }
            List<IotDeviceFormIndex> rows = formIndexMapper.selectList(indexQuery);
            Set<Long> ids = new HashSet<>();
            for (IotDeviceFormIndex row : rows) {
                ids.add(row.getDeviceId());
            }
            if (result == null) {
                result = ids;
            } else {
                result.retainAll(ids);
            }
            if (result.isEmpty()) {
                return result;
            }
        }
        return result;
    }

    private void rebuildIndex(IotDevice device, IotProduct product, Map<String, Object> stored) {
        formIndexMapper.delete(new LambdaQueryWrapper<IotDeviceFormIndex>()
                                   .eq(IotDeviceFormIndex::getDeviceId, device.getDeviceId()));
        for (DeviceFormSupport.IndexRow row : formSupport.indexRows(product.getDeviceFormSchema(), stored)) {
            IotDeviceFormIndex idx = new IotDeviceFormIndex();
            idx.setDeviceId(device.getDeviceId());
            idx.setProductId(device.getProductId());
            idx.setSchemaVersion(product.getDeviceFormVersion());
            idx.setFieldKey(row.fieldKey());
            idx.setValueText(row.valueText());
            idx.setValueDecimal(row.valueDecimal());
            idx.setValueBoolean(row.valueBoolean());
            formIndexMapper.insert(idx);
        }
    }

    private void applyGatewayRule(IotDevice device, Long gatewayId, int nodeType) {
        if (nodeType == 3) {
            if (gatewayId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3006, "子设备必须指定 gatewayId");
            }
            IotDevice gw = deviceMapper.selectById(gatewayId);
            if (gw == null || !Objects.equals(gw.getNodeType(), 2)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3006, "gatewayId 必须指向网关设备");
            }
            device.setGatewayId(gatewayId);
        } else {
            if (gatewayId != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3006, "直连/网关不得带 gatewayId");
            }
            device.setGatewayId(null);
        }
    }

    private void assertCodeUnique(Long productId, String code, Long excludeId) {
        LambdaQueryWrapper<IotDevice> w = new LambdaQueryWrapper<IotDevice>()
            .eq(IotDevice::getProductId, productId)
            .eq(IotDevice::getDeviceCode, code);
        if (excludeId != null) {
            w.ne(IotDevice::getDeviceId, excludeId);
        }
        if (deviceMapper.selectCount(w) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 3005, "device_code 产品内冲突");
        }
    }

    private IotProduct requireProduct(Long productId) {
        IotProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3001, "产品不存在");
        }
        return product;
    }

    private IotTmProperty requireProperty(Long productId, String identifier) {
        IotTmProperty property = propertyMapper.selectOne(
            new LambdaQueryWrapper<IotTmProperty>()
                .eq(IotTmProperty::getProductId, productId)
                .eq(IotTmProperty::getIdentifier, identifier));
        if (property == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3018, "设备所属产品不存在属性: " + identifier);
        }
        return property;
    }

    private IotDevice require(Long deviceId) {
        IotDevice device = deviceMapper.selectById(deviceId);
        if (device == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3004, "设备不存在");
        }
        return device;
    }

    private DeviceVO toVo(IotDevice device, IotProduct product, boolean editMode) {
        DeviceVO vo = deviceConverter.toVO(device);
        String schemaJson = product == null ? null : product.getDeviceFormSchema();
        vo.setDeviceFormData(formSupport.toDisplayFormData(schemaJson, device.getDeviceFormData(), editMode));
        if (product != null) {
            deviceConverter.copyProduct(vo, product);
        }
        return vo;
    }
}
