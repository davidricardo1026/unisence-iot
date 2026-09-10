package com.unisence.iot.admin.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.unisence.iot.admin.device.converter.*;
import com.unisence.iot.admin.device.dto.*;
import com.unisence.iot.admin.device.support.CodeGenerator;
import com.unisence.iot.admin.device.support.DeviceFormSupport;
import com.unisence.iot.admin.device.support.JsonMaps;
import com.unisence.iot.admin.device.vo.*;
import com.unisence.iot.admin.entity.*;
import com.unisence.iot.admin.mapper.*;
import com.unisence.iot.admin.metadata.MetadataCommit;
import com.unisence.iot.admin.rule.service.IotRuleRouteService;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import com.unisence.iot.common.constant.MetaKeyEnum;
import com.unisence.iot.common.exception.BusinessException;
import com.unisence.iot.common.metadata.MetadataScope;
import com.unisence.iot.common.service.BaseServiceImpl;
import com.unisence.iot.rule.sdk.DataRetention;
import com.unisence.iot.rule.sdk.EventDataRetention;
import com.unisence.iot.rule.sdk.PropertyDataType;
import com.unisence.iot.timeseries.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IotProductServiceImpl extends BaseServiceImpl<IotProductMapper, IotProduct> implements IotProductService {

    private static final Set<String> MODEL_DATA_TYPES = Set.of("int",
                                                               "float",
                                                               "double",
                                                               "bool",
                                                               "enum",
                                                               "string",
                                                               "text",
                                                               "image");

    private final IotProductMapper productMapper;
    private final IotDeviceMapper deviceMapper;
    private final IotDeviceFormIndexMapper formIndexMapper;
    private final IotTagMapper tagMapper;
    private final IotProductTagMapper productTagMapper;
    private final IotTmPropertyMapper propertyMapper;
    private final IotTmEventMapper eventMapper;
    private final IotTmServiceMapper serviceMapper;
    private final IotRuleInstantProductMapper instantRuleProductMapper;
    private final IotRuleWindowProductMapper windowRuleProductMapper;
    private final IotRuleRouteProductMapper routeRuleProductMapper;
    private final IotRuleRouteService routeService;
    private final IotProductConverter productConverter;
    private final IotTagConverter tagConverter;
    private final IotTmPropertyConverter propertyConverter;
    private final IotTmEventConverter eventConverter;
    private final IotTmServiceConverter serviceConverter;
    private final CodeGenerator codeGenerator;
    private final JsonMaps jsonMaps;
    private final DeviceFormSupport formSupport;
    private final MetadataCommit metadataCommit;
    private final TimeSeriesProvisioner timeSeriesProvisioner;
    private final PlatformTransactionManager transactionManager;

    @Override
    public PageResult<ProductVO> pageProducts(PageRequest<ProductQuery> request) {
        ProductQuery query = request.getQuery();
        LambdaQueryWrapper<IotProduct> wrapper = new LambdaQueryWrapper<>();
        int productType = query != null && query.getProductType() != null ? query.getProductType() : 1;
        wrapper.eq(IotProduct::getProductType, productType);
        if (query != null) {
            if (StringUtils.hasText(query.getKeyword())) {
                wrapper.and(w -> w.like(IotProduct::getProductName, query.getKeyword())
                    .or()
                    .eq(IotProduct::getProductKey, query.getKeyword()));
            }
            wrapper.like(StringUtils.hasText(query.getProductName()),
                         IotProduct::getProductName,
                         query.getProductName())
                .eq(StringUtils.hasText(query.getProductKey()), IotProduct::getProductKey, query.getProductKey())
                .eq(query.getNodeType() != null, IotProduct::getNodeType, query.getNodeType());
            if (query.getTagId() != null) {
                List<Long> ids = productTagMapper.selectList(
                        new LambdaQueryWrapper<IotProductTag>().eq(IotProductTag::getTagId, query.getTagId()))
                    .stream().map(IotProductTag::getProductId).toList();
                if (ids.isEmpty()) {
                    return new PageResult<>(List.of(), 0);
                }
                wrapper.in(IotProduct::getProductId, ids);
            }
        }
        wrapper.orderByDesc(IotProduct::getCreateTime);
        Page<IotProduct> page = productMapper.selectPage(new Page<>(request.getPageNum(), request.getPageSize()),
                                                         wrapper);
        List<ProductVO> list = page.getRecords().stream().map(p -> toVo(p, false)).toList();
        return new PageResult<>(list, page.getTotal());
    }

    @Override
    public ProductVO getProduct(Long productId) {
        return toVo(require(productId), true);
    }

    @Override
    @Transactional
    public Long createProduct(ProductSaveRequest request) {
        formSupport.validateSchema(request.getDeviceFormSchema());
        IotProduct product = productConverter.toEntity(request);
        applyJson(product, request);
        product.setProductKey(nextUniqueProductKey());
        if (product.getProductType() == null) {
            product.setProductType(1);
        }
        productMapper.insert(product);
        metadataCommit.commit(MetaKeyEnum.IOT_PRODUCT, product.getProductId());
        return product.getProductId();
    }

    @Override
    @Transactional
    public void updateProduct(Long productId, ProductSaveRequest request) {
        IotProduct product = require(productId);
        formSupport.validateSchema(request.getDeviceFormSchema());
        formSupport.assertKeyEvolution(product.getDeviceFormSchema(), request.getDeviceFormSchema());
        String previousSchema = product.getDeviceFormSchema();
        product.setVersion(request.getVersion());
        productConverter.updateEntity(product, request);
        applyJson(product, request);
        // 表单 schema 真正变化时才推进 deviceFormVersion：产品改名、换图标等非 schema 变化若也推进，
        // 会让该产品下全部设备的 L1/L2 投影因版本不匹配一次性失效，白白击穿整个产品的缓存
        if (!Objects.equals(previousSchema, product.getDeviceFormSchema())) {
            product.setDeviceFormVersion(
                (product.getDeviceFormVersion() == null ? 0 : product.getDeviceFormVersion()) + 1);
        }
        productMapper.updateByIdWithVersionCheck(product);
        rebuildProductFormIndex(product);
        metadataCommit.commit(MetaKeyEnum.IOT_PRODUCT, productId);
    }

    @Override
    @Transactional
    public void deleteProduct(Long productId) {
        // 先算范围再删行：范围里含「绑定了本产品的规则」，绑定行被删掉后就查不出来了
        Set<MetadataScope> scopes = deleteScopes(productId);
        deleteProductRows(productId);
        metadataCommit.commit(scopes);
    }

    @Override
    @Transactional
    public void batchDeleteProducts(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3001, "请选择要删除的产品");
        }
        List<Long> distinctIds = productIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.size() != productIds.size()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3001, "产品编号不能为空或重复");
        }
        for (Long productId : distinctIds) {
            require(productId);
        }
        // 逐个删行、最后统一记一次变化范围：一个最外层事务只允许分配一个 commit_seq，
        // 若沿用「循环调用 deleteProduct」会在第二次进入时因重复分配而失败
        Set<MetadataScope> scopes = new LinkedHashSet<>();
        for (Long productId : distinctIds) {
            scopes.addAll(deleteScopes(productId));
            deleteProductRows(productId);
        }
        metadataCommit.commit(scopes);
    }

    /**
     * 删除一个产品的业务行，不记录变化范围 —— 由调用方在事务末尾统一记录。
     */
    private void deleteProductRows(Long productId) {
        require(productId);
        Long cnt = deviceMapper.selectCount(new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getProductId, productId));
        if (cnt != null && cnt > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 3012, "产品下仍有设备，禁止删除");
        }
        productTagMapper.delete(new LambdaQueryWrapper<IotProductTag>().eq(IotProductTag::getProductId, productId));
        // 规则绑定是 B 类关联，随产品物理删除；留着会让规则指向一个不存在的产品，
        // 下次启用或改绑时的重校验会读不到物模型而报「产品不存在」。
        // 即时规则、窗口规则与透传路由各有一张绑定表，都要清 —— 漏一张就等于漏一类规则
        routeService.detachProducts(List.of(productId));
        instantRuleProductMapper.delete(
            new LambdaQueryWrapper<IotRuleInstantProduct>().eq(IotRuleInstantProduct::getProductId, productId));
        windowRuleProductMapper.delete(
            new LambdaQueryWrapper<IotRuleWindowProduct>().eq(IotRuleWindowProduct::getProductId, productId));
        softDeleteTm(productId);
        removeById(productId);
    }

    /**
     * 删除产品的失效范围。
     *
     * <p>产品下仍有设备时已在上面拒绝删除，因此这里<b>不</b>级联生成设备 scope ——
     * 百万设备产品若逐台记录会直接压垮变更目录。
     *
     * <p>绑定了本产品的规则必须逐条记 {@code IOT_RULES(ruleId)}：engine 的规则索引是
     * {@code (productKey, messageType) → 规则列表}，产品没了索引就得重建，
     * 而规则行本身未被修改，只记 {@code IOT_PRODUCT} 是推不动规则域的。
     *
     * <p><b>必须在删除绑定行之前调用</b> —— 见 {@link #deleteProduct(Long)} 的调用顺序。
     * 这里不对受影响规则做重校验：少一个产品只会让规则的作用范围收窄，
     * 不会让它变成非法规则，没有理由因为删产品而连带拒绝。
     */
    private Set<MetadataScope> deleteScopes(Long productId) {
        Set<MetadataScope> scopes = new LinkedHashSet<>();
        scopes.add(MetadataScope.of(MetaKeyEnum.IOT_PRODUCT, productId));
        scopes.add(MetadataScope.of(MetaKeyEnum.IOT_THING_MODEL, productId));
        for (IotRuleInstantProduct binding : instantRuleProductMapper.selectList(
            new LambdaQueryWrapper<IotRuleInstantProduct>().eq(IotRuleInstantProduct::getProductId, productId))) {
            scopes.add(MetadataScope.of(MetaKeyEnum.IOT_RULES, binding.getRuleId()));
        }
        for (IotRuleWindowProduct binding : windowRuleProductMapper.selectList(
            new LambdaQueryWrapper<IotRuleWindowProduct>().eq(IotRuleWindowProduct::getProductId, productId))) {
            scopes.add(MetadataScope.of(MetaKeyEnum.IOT_RULES, binding.getRuleId()));
        }
        for (IotRuleRouteProduct binding : routeRuleProductMapper.selectList(
            new LambdaQueryWrapper<IotRuleRouteProduct>().eq(IotRuleRouteProduct::getProductId, productId))) {
            scopes.add(MetadataScope.of(MetaKeyEnum.IOT_RULES, binding.getRuleId()));
        }
        return scopes;
    }

    @Override
    public ProductVO cloneProduct(Long productId, ProductCloneRequest request) {
        IotProduct src = require(productId);
        if (!Objects.equals(src.getProductType(), 2)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3001, "仅标准产品可创建");
        }
        String productKey;
        boolean randomKey = request != null && "random".equals(request.getKeyStrategy());
        if (randomKey) {
            productKey = nextUniqueProductKey();
        } else {
            Long existing = productMapper.selectCount(new LambdaQueryWrapper<IotProduct>()
                                                          .eq(IotProduct::getProductKey, src.getProductKey())
                                                          .eq(IotProduct::getProductType, 1));
            if (existing != null && existing > 0) {
                throw new BusinessException(HttpStatus.CONFLICT, 3011, "该产品编码已存在普通产品；可选择随机生成新编码");
            }
            productKey = src.getProductKey();
        }
        String productName = StringUtils.hasText(request != null ? request.getProductName() : null)
            ? request.getProductName()
            : src.getProductName() + "-副本";
        List<IotTmEvent> sourceEvents = eventMapper.selectList(
            new LambdaQueryWrapper<IotTmEvent>().eq(IotTmEvent::getProductId, productId));
        for (IotTmEvent event : sourceEvents) {
            provisionEventTable(productKey, event.getIdentifier(), jsonMaps.readMapList(event.getInputParams()),
                                event.getTtlEnabled(), event.getTtlValue(), event.getTtlUnit(), true);
        }
        return new TransactionTemplate(transactionManager).execute(status -> {
            IotProduct neo = productConverter.copy(src);
            neo.setProductKey(productKey);
            neo.setProductName(productName);
            neo.setProductType(1);
            productMapper.insert(neo);

            for (IotProductTag sourceTag : productTagMapper.selectList(
                new LambdaQueryWrapper<IotProductTag>().eq(IotProductTag::getProductId, productId))) {
                IotProductTag targetTag = new IotProductTag();
                targetTag.setProductId(neo.getProductId());
                targetTag.setTagId(sourceTag.getTagId());
                productTagMapper.insert(targetTag);
            }

            for (IotTmProperty p : propertyMapper.selectList(new LambdaQueryWrapper<IotTmProperty>().eq(IotTmProperty::getProductId,
                                                                                                        productId))) {
                IotTmProperty c = propertyConverter.copy(p);
                c.setProductId(neo.getProductId());
                propertyMapper.insert(c);
            }
            for (IotTmEvent e : sourceEvents) {
                IotTmEvent c = eventConverter.copy(e);
                c.setProductId(neo.getProductId());
                eventMapper.insert(c);
            }
            for (IotTmService s : serviceMapper.selectList(new LambdaQueryWrapper<IotTmService>().eq(IotTmService::getProductId,
                                                                                                     productId))) {
                IotTmService c = serviceConverter.copy(s);
                c.setProductId(neo.getProductId());
                serviceMapper.insert(c);
            }
            metadataCommit.commit(MetadataCommit.scopes(
                MetadataScope.of(MetaKeyEnum.IOT_PRODUCT, neo.getProductId()),
                MetadataScope.of(MetaKeyEnum.IOT_THING_MODEL, neo.getProductId())));
            return toVo(neo, true);
        });
    }

    @Override
    @Transactional
    public void replaceTags(Long productId, ProductTagsRequest request) {
        require(productId);
        productTagMapper.delete(new LambdaQueryWrapper<IotProductTag>().eq(IotProductTag::getProductId, productId));
        if (request.getTagIds() == null) {
            return;
        }
        for (Long tagId : request.getTagIds()) {
            if (tagMapper.selectById(tagId) == null) {
                throw new BusinessException(HttpStatus.NOT_FOUND, 3010, "标签不存在: " + tagId);
            }
            IotProductTag rel = new IotProductTag();
            rel.setProductId(productId);
            rel.setTagId(tagId);
            productTagMapper.insert(rel);
        }
    }

    @Override
    public List<TmPropertyVO> listProperties(Long productId) {
        require(productId);
        return propertyMapper.selectList(new LambdaQueryWrapper<IotTmProperty>()
                                             .eq(IotTmProperty::getProductId, productId)
                                             .orderByAsc(IotTmProperty::getPropertyId))
            .stream().map(propertyConverter::toVO).toList();
    }

    @Override
    @Transactional
    public Long createProperty(Long productId, TmPropertySaveRequest request) {
        require(productId);
        validatePropertyStrategy(request);
        assertPropIdentifierUnique(productId, request.getIdentifier(), null);
        IotTmProperty entity = propertyConverter.toEntity(request);
        entity.setProductId(productId);
        if (entity.getAccessMode() == null) {
            entity.setAccessMode(1);
        }
        propertyMapper.insert(entity);
        metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
        return entity.getPropertyId();
    }

    @Override
    @Transactional
    public void updateProperty(Long productId, Long propertyId, TmPropertySaveRequest request) {
        require(productId);
        validatePropertyStrategy(request);
        IotTmProperty entity = requireProp(productId, propertyId);
        if (!Objects.equals(entity.getDataType(), request.getDataType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3028, "属性数据类型创建后不可修改");
        }
        if (!Objects.equals(entity.getRetentionDays(), request.getRetentionDays())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3029, "属性历史保留档位创建后不可修改");
        }
        entity.setVersion(request.getVersion());
        propertyConverter.updateEntity(entity, request);
        if (entity.getAccessMode() == null) {
            entity.setAccessMode(1);
        }
        propertyMapper.updateByIdWithVersionCheck(entity);
        metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
    }

    @Override
    @Transactional
    public void deleteProperty(Long productId, Long propertyId) {
        require(productId);
        requireProp(productId, propertyId);
        softDeleteProperty(productId, propertyId);
        metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
    }

    @Override
    public List<TmEventVO> listEvents(Long productId) {
        require(productId);
        return eventMapper.selectList(new LambdaQueryWrapper<IotTmEvent>()
                                          .eq(IotTmEvent::getProductId, productId)
                                          .orderByAsc(IotTmEvent::getEventId))
            .stream().map(this::toEventVo).toList();
    }

    @Override
    public Long createEvent(Long productId, TmEventSaveRequest request) {
        IotProduct product = require(productId);
        canonicalizeEventIdentifiers(request);
        validateEventIdentifier(request.getIdentifier());
        validateEventRetention(request);
        assertEventIdentifierUnique(productId, request.getIdentifier(), null);
        validateEventParams(request.getInputParams());
        if (Objects.equals(product.getProductType(), 1)) {
            provisionEventTable(product.getProductKey(), request.getIdentifier(), request.getInputParams(),
                                request.getTtlEnabled(), request.getTtlValue(), request.getTtlUnit(), true);
        }
        return new TransactionTemplate(transactionManager).execute(status -> {
            IotTmEvent entity = eventConverter.toEntity(request);
            entity.setProductId(productId);
            if (entity.getEventType() == null) {
                entity.setEventType(1);
            }
            entity.setInputParams(jsonMaps.write(request.getInputParams()));
            eventMapper.insert(entity);
            metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
            return entity.getEventId();
        });
    }

    @Override
    public void updateEvent(Long productId, Long eventId, TmEventSaveRequest request) {
        IotProduct product = require(productId);
        canonicalizeEventIdentifiers(request);
        validateEventRetention(request);
        validateEventParams(request.getInputParams());
        IotTmEvent entity = requireEvent(productId, eventId);
        rejectParamTypeChanges(entity, request.getInputParams());
        boolean ttlChanged = !Objects.equals(entity.getTtlEnabled(), request.getTtlEnabled())
            || !Objects.equals(entity.getTtlValue(), request.getTtlValue())
            || !Objects.equals(entity.getTtlUnit(), request.getTtlUnit());
        boolean columnsChanged = !eventColumnSignature(jsonMaps.readMapList(entity.getInputParams()))
            .equals(eventColumnSignature(request.getInputParams()));
        if (Objects.equals(product.getProductType(), 1) && (ttlChanged || columnsChanged)) {
            provisionEventTable(product.getProductKey(), entity.getIdentifier(), request.getInputParams(),
                                request.getTtlEnabled(), request.getTtlValue(), request.getTtlUnit(), ttlChanged);
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entity.setVersion(request.getVersion());
            eventConverter.updateEntity(entity, request);
            entity.setInputParams(jsonMaps.write(request.getInputParams()));
            eventMapper.updateByIdWithVersionCheck(entity);
            metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
        });
    }

    @Override
    @Transactional
    public void deleteEvent(Long productId, Long eventId) {
        require(productId);
        requireEvent(productId, eventId);
        softDeleteEvent(productId, eventId);
        metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
    }

    @Override
    public List<TmServiceVO> listServices(Long productId) {
        require(productId);
        return serviceMapper.selectList(new LambdaQueryWrapper<IotTmService>()
                                            .eq(IotTmService::getProductId, productId)
                                            .orderByAsc(IotTmService::getServiceId))
            .stream().map(this::toSvcVo).toList();
    }

    @Override
    @Transactional
    public Long createService(Long productId, TmServiceSaveRequest request) {
        require(productId);
        assertSvcIdentifierUnique(productId, request.getIdentifier(), null);
        validateParams(request.getInputParams(), "输入参数");
        validateParams(request.getOutputParams(), "输出参数");
        IotTmService entity = serviceConverter.toEntity(request);
        entity.setProductId(productId);
        entity.setInputParams(jsonMaps.write(request.getInputParams()));
        entity.setOutputParams(jsonMaps.write(request.getOutputParams()));
        if (entity.getCallType() == null) {
            entity.setCallType(1);
        }
        serviceMapper.insert(entity);
        metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
        return entity.getServiceId();
    }

    @Override
    @Transactional
    public void updateService(Long productId, Long serviceId, TmServiceSaveRequest request) {
        require(productId);
        validateParams(request.getInputParams(), "输入参数");
        validateParams(request.getOutputParams(), "输出参数");
        IotTmService entity = requireSvc(productId, serviceId);
        entity.setVersion(request.getVersion());
        serviceConverter.updateEntity(entity, request);
        entity.setInputParams(jsonMaps.write(request.getInputParams()));
        entity.setOutputParams(jsonMaps.write(request.getOutputParams()));
        serviceMapper.updateByIdWithVersionCheck(entity);
        metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
    }

    @Override
    @Transactional
    public void deleteService(Long productId, Long serviceId) {
        require(productId);
        requireSvc(productId, serviceId);
        softDeleteService(productId, serviceId);
        metadataCommit.commit(MetaKeyEnum.IOT_THING_MODEL, productId);
    }

    private void softDeleteTm(Long productId) {
        for (IotTmProperty p : propertyMapper.selectList(new LambdaQueryWrapper<IotTmProperty>().eq(IotTmProperty::getProductId,
                                                                                                    productId))) {
            softDeleteProperty(productId, p.getPropertyId());
        }
        for (IotTmEvent e : eventMapper.selectList(new LambdaQueryWrapper<IotTmEvent>().eq(IotTmEvent::getProductId,
                                                                                           productId))) {
            softDeleteEvent(productId, e.getEventId());
        }
        for (IotTmService s : serviceMapper.selectList(new LambdaQueryWrapper<IotTmService>().eq(IotTmService::getProductId,
                                                                                                 productId))) {
            softDeleteService(productId, s.getServiceId());
        }
    }

    /**
     * 物模型子表不属于当前 Product Service 的泛型实体，不能调用 this.removeById。
     * 因此按平台逻辑删除契约显式更新：仅删除当前产品下仍处于 deleted=0 的那一条，并把 deleted 写为自身主键。
     */
    private void softDeleteProperty(Long productId, Long propertyId) {
        propertyMapper.update(null, new LambdaUpdateWrapper<IotTmProperty>()
            .setSql("deleted = " + propertyId)
            .eq(IotTmProperty::getProductId, productId)
            .eq(IotTmProperty::getPropertyId, propertyId)
            .eq(IotTmProperty::getDeleted, 0L));
    }

    private void softDeleteEvent(Long productId, Long eventId) {
        eventMapper.update(null, new LambdaUpdateWrapper<IotTmEvent>()
            .setSql("deleted = " + eventId)
            .eq(IotTmEvent::getProductId, productId)
            .eq(IotTmEvent::getEventId, eventId)
            .eq(IotTmEvent::getDeleted, 0L));
    }

    private void softDeleteService(Long productId, Long serviceId) {
        serviceMapper.update(null, new LambdaUpdateWrapper<IotTmService>()
            .setSql("deleted = " + serviceId)
            .eq(IotTmService::getProductId, productId)
            .eq(IotTmService::getServiceId, serviceId)
            .eq(IotTmService::getDeleted, 0L));
    }

    private void applyJson(IotProduct product, ProductSaveRequest request) {
        product.setAttributes(jsonMaps.write(request.getAttributes()));
        product.setDeviceFormSchema(jsonMaps.write(request.getDeviceFormSchema()));
    }

    /**
     * 设备模板的 searchable、字段类型或字段删除发生变化后，必须在同一事务内重建该产品全部设备的索引。
     * 已删除字段的实例值一并移除，避免遗留不可见、不可解释的数据键。
     */
    private void rebuildProductFormIndex(IotProduct product) {
        List<IotDevice> devices = deviceMapper.selectList(
            new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getProductId, product.getProductId()));
        formIndexMapper.delete(new LambdaQueryWrapper<IotDeviceFormIndex>()
                                   .eq(IotDeviceFormIndex::getProductId, product.getProductId()));
        Set<String> activeFieldKeys = formSupport.fieldKeys(product.getDeviceFormSchema());
        for (IotDevice device : devices) {
            Map<String, Object> formData = new LinkedHashMap<>(jsonMaps.readMap(device.getDeviceFormData()));
            if (formData.keySet().removeIf(key -> !activeFieldKeys.contains(key))) {
                device.setDeviceFormData(jsonMaps.write(formData));
                deviceMapper.updateByIdWithVersionCheck(device);
            }
            for (DeviceFormSupport.IndexRow row : formSupport.indexRows(product.getDeviceFormSchema(), formData)) {
                IotDeviceFormIndex index = new IotDeviceFormIndex();
                index.setDeviceId(device.getDeviceId());
                index.setProductId(product.getProductId());
                index.setFieldKey(row.fieldKey());
                index.setValueText(row.valueText());
                index.setValueDecimal(row.valueDecimal());
                index.setValueBoolean(row.valueBoolean());
                formIndexMapper.insert(index);
            }
        }
    }

    private String nextUniqueProductKey() {
        for (int i = 0; i < 8; i++) {
            String key = codeGenerator.nextProductKey();
            Long cnt = productMapper.selectCount(new LambdaQueryWrapper<IotProduct>().eq(IotProduct::getProductKey,
                                                                                         key));
            if (cnt == null || cnt == 0) {
                return key;
            }
        }
        throw new BusinessException(HttpStatus.CONFLICT, 3002, "product_key 生成冲突，请重试");
    }

    private void validatePropertyStrategy(TmPropertySaveRequest request) {
        if (!MODEL_DATA_TYPES.contains(request.getDataType())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "不支持的属性数据类型: " + request.getDataType());
        }
        validateRetentionDays(request.getRetentionDays());
    }

    private void validateRetentionDays(Integer retentionDays) {
        try {
            DataRetention.requireAllowed(retentionDays == null ? -1 : retentionDays);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "保留时间只允许 90、180、360 天");
        }
    }

    private void validateEventRetention(TmEventSaveRequest request) {
        if (request.getTtlEnabled() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "请选择事件数据保留策略");
        }
        try {
            EventDataRetention.requireValid(request.getTtlEnabled(),
                                            request.getTtlValue(), request.getTtlUnit());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, e.getMessage());
        }
    }

    private void validateParams(List<Map<String, Object>> params, String label) {
        if (params == null) {
            return;
        }
        Set<String> identifiers = new java.util.HashSet<>();
        for (Map<String, Object> param : params) {
            String identifier = String.valueOf(param.getOrDefault("identifier", "")).trim();
            String name = String.valueOf(param.getOrDefault("name", "")).trim();
            String dataType = String.valueOf(param.getOrDefault("dataType", "")).trim();
            if (!StringUtils.hasText(identifier) || !StringUtils.hasText(name) || !MODEL_DATA_TYPES.contains(dataType)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, label + "的标识符、名称和有效数据类型必填");
            }
            if (!identifiers.add(identifier.toLowerCase(Locale.ROOT))) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, label + "标识符重复: " + identifier);
            }
        }
    }

    private IotProduct require(Long productId) {
        IotProduct product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3001, "产品不存在");
        }
        return product;
    }

    private ProductVO toVo(IotProduct product, boolean detail) {
        ProductVO vo = productConverter.toVO(product);
        if (detail) {
            vo.setAttributes(jsonMaps.readMap(product.getAttributes()));
            vo.setDeviceFormSchema(jsonMaps.readMap(product.getDeviceFormSchema()));
            vo.setTags(loadTags(product.getProductId()));
        }
        return vo;
    }

    private List<TagVO> loadTags(Long productId) {
        List<Long> tagIds = productTagMapper.selectList(
                new LambdaQueryWrapper<IotProductTag>().eq(IotProductTag::getProductId, productId))
            .stream().map(IotProductTag::getTagId).toList();
        if (tagIds.isEmpty()) {
            return List.of();
        }
        return tagMapper.selectBatchIds(tagIds).stream()
            .map(tagConverter::toVO)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private TmEventVO toEventVo(IotTmEvent e) {
        TmEventVO vo = eventConverter.toVO(e);
        vo.setInputParams(jsonMaps.readMapList(e.getInputParams()));
        return vo;
    }

    private TmServiceVO toSvcVo(IotTmService s) {
        TmServiceVO vo = serviceConverter.toVO(s);
        vo.setInputParams(jsonMaps.readMapList(s.getInputParams()));
        vo.setOutputParams(jsonMaps.readMapList(s.getOutputParams()));
        return vo;
    }

    private void assertPropIdentifierUnique(Long productId, String identifier, Long excludeId) {
        LambdaQueryWrapper<IotTmProperty> w = new LambdaQueryWrapper<IotTmProperty>()
            .eq(IotTmProperty::getProductId, productId)
            .eq(IotTmProperty::getIdentifier, identifier);
        if (excludeId != null) {
            w.ne(IotTmProperty::getPropertyId, excludeId);
        }
        if (propertyMapper.selectCount(w) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 3007, "属性标识符已存在");
        }
    }

    private void canonicalizeEventIdentifiers(TmEventSaveRequest request) {
        if (request.getIdentifier() != null) {
            request.setIdentifier(request.getIdentifier().trim().toLowerCase(Locale.ROOT));
        }
        if (request.getInputParams() == null) {
            return;
        }
        for (Map<String, Object> param : request.getInputParams()) {
            Object raw = param.get("identifier");
            if (raw != null) {
                param.put("identifier", String.valueOf(raw).trim().toLowerCase(Locale.ROOT));
            }
            param.remove("columnKind");
        }
    }

    private void validateEventIdentifier(String identifier) {
        try {
            EventTableNames.requireIdentifier(identifier);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "事件标识符格式非法");
        }
    }

    private void validateEventParams(List<Map<String, Object>> params) {
        validateParams(params, "输入参数");
        if (params == null) {
            return;
        }
        for (Map<String, Object> param : params) {
            String identifier = String.valueOf(param.getOrDefault("identifier", "")).trim();
            try {
                EventTableNames.columnName(identifier);
            } catch (IllegalArgumentException e) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, "事件参数标识符非法: " + identifier);
            }
        }
    }

    private void rejectParamTypeChanges(IotTmEvent entity, List<Map<String, Object>> params) {
        Map<String, String> previous = new LinkedHashMap<>();
        for (Map<String, Object> param : jsonMaps.readMapList(entity.getInputParams())) {
            String identifier = String.valueOf(param.getOrDefault("identifier", "")).trim().toLowerCase(Locale.ROOT);
            String dataType = String.valueOf(param.getOrDefault("dataType", "")).trim();
            if (StringUtils.hasText(identifier)) {
                previous.put(identifier, dataType);
            }
        }
        if (params == null) {
            return;
        }
        for (Map<String, Object> param : params) {
            String identifier = String.valueOf(param.getOrDefault("identifier", "")).trim().toLowerCase(Locale.ROOT);
            String dataType = String.valueOf(param.getOrDefault("dataType", "")).trim();
            String oldType = previous.get(identifier);
            if (oldType != null && !oldType.equals(dataType)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, 3026,
                                            "禁止修改已落地事件参数列的类型: param=" + identifier);
            }
        }
    }

    private void provisionEventTable(String productKey, String identifier, List<Map<String, Object>> params,
                                     boolean ttlEnabled, Integer ttlValue, String ttlUnit, boolean applyTtlChange) {
        try {
            timeSeriesProvisioner.ensureEventTable(
                new EventTableSpec(productKey, identifier, eventColumns(params), ttlEnabled, ttlValue, ttlUnit),
                applyTtlChange);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3007, e.getMessage());
        } catch (EventColumnTypeConflictException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, 3026, e.getMessage());
        } catch (EventTableProvisionException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 3025,
                                        "事件时序表供给失败: productKey=" + productKey
                                            + " identifier=" + identifier
                                            + (e.getCause() != null ? " " + e.getCause().getMessage() : " " + e.getMessage()));
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, 3025,
                                        "事件时序表供给失败: productKey=" + productKey
                                            + " identifier=" + identifier + " " + e.getMessage());
        }
    }

    private static List<EventTableColumn> eventColumns(List<Map<String, Object>> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<EventTableColumn> columns = new ArrayList<>(params.size());
        for (Map<String, Object> param : params) {
            String identifier = String.valueOf(param.getOrDefault("identifier", "")).trim();
            String dataType = String.valueOf(param.getOrDefault("dataType", "")).trim();
            columns.add(new EventTableColumn(identifier, PropertyDataType.fromCode(dataType)));
        }
        return columns;
    }

    private static Map<String, PropertyDataType> eventColumnSignature(List<Map<String, Object>> params) {
        Map<String, PropertyDataType> signature = new LinkedHashMap<>();
        for (EventTableColumn column : eventColumns(params)) {
            signature.put(column.paramIdentifier().toLowerCase(Locale.ROOT), column.dataType());
        }
        return signature;
    }

    private void assertEventIdentifierUnique(Long productId, String identifier, Long excludeId) {
        LambdaQueryWrapper<IotTmEvent> w = new LambdaQueryWrapper<IotTmEvent>()
            .eq(IotTmEvent::getProductId, productId)
            .eq(IotTmEvent::getIdentifier, identifier);
        if (excludeId != null) {
            w.ne(IotTmEvent::getEventId, excludeId);
        }
        if (eventMapper.selectCount(w) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 3007, "事件标识符已存在");
        }
    }

    private void assertSvcIdentifierUnique(Long productId, String identifier, Long excludeId) {
        LambdaQueryWrapper<IotTmService> w = new LambdaQueryWrapper<IotTmService>()
            .eq(IotTmService::getProductId, productId)
            .eq(IotTmService::getIdentifier, identifier);
        if (excludeId != null) {
            w.ne(IotTmService::getServiceId, excludeId);
        }
        if (serviceMapper.selectCount(w) > 0) {
            throw new BusinessException(HttpStatus.CONFLICT, 3007, "服务标识符已存在");
        }
    }

    private IotTmProperty requireProp(Long productId, Long propertyId) {
        IotTmProperty entity = propertyMapper.selectById(propertyId);
        if (entity == null || !Objects.equals(entity.getProductId(), productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3001, "属性不存在");
        }
        return entity;
    }

    private IotTmEvent requireEvent(Long productId, Long eventId) {
        IotTmEvent entity = eventMapper.selectById(eventId);
        if (entity == null || !Objects.equals(entity.getProductId(), productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3001, "事件不存在");
        }
        return entity;
    }

    private IotTmService requireSvc(Long productId, Long serviceId) {
        IotTmService entity = serviceMapper.selectById(serviceId);
        if (entity == null || !Objects.equals(entity.getProductId(), productId)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, 3001, "服务不存在");
        }
        return entity;
    }
}
