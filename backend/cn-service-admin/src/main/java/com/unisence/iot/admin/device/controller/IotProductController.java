package com.unisence.iot.admin.device.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.unisence.iot.admin.aop.annotation.BusinessType;
import com.unisence.iot.admin.aop.annotation.OperLog;
import com.unisence.iot.admin.device.dto.*;
import com.unisence.iot.admin.device.service.IotProductService;
import com.unisence.iot.admin.device.vo.ProductVO;
import com.unisence.iot.admin.device.vo.TmEventVO;
import com.unisence.iot.admin.device.vo.TmPropertyVO;
import com.unisence.iot.admin.device.vo.TmServiceVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/iot/products")
@RequiredArgsConstructor
public class IotProductController {

    private final IotProductService productService;

    @GetMapping
    @SaCheckPermission("iot:product:list")
    public PageResult<ProductVO> pageProducts(PageRequest<ProductQuery> request) {
        return productService.pageProducts(request);
    }

    @GetMapping("/{productId}")
    @SaCheckPermission("iot:product:list")
    public ProductVO getProduct(@PathVariable Long productId) {
        return productService.getProduct(productId);
    }

    @PostMapping
    @OperLog(title = "产品管理", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:product:add")
    public Long createProduct(@RequestBody @Valid ProductSaveRequest request) {
        return productService.createProduct(request);
    }

    @PutMapping("/{productId}")
    @OperLog(title = "产品管理", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:product:edit")
    public void updateProduct(@PathVariable Long productId, @RequestBody @Valid ProductSaveRequest request) {
        productService.updateProduct(productId, request);
    }

    @DeleteMapping("/{productId}")
    @OperLog(title = "产品管理", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:product:remove")
    public void deleteProduct(@PathVariable Long productId) {
        productService.deleteProduct(productId);
    }

    @DeleteMapping("/batch")
    @OperLog(title = "产品批量删除", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:product:remove")
    public void batchDeleteProducts(@RequestBody List<Long> productIds) {
        productService.batchDeleteProducts(productIds);
    }

    @PostMapping("/{productId}/clone")
    @OperLog(title = "产品管理", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:product:clone")
    public ProductVO cloneProduct(@PathVariable Long productId,
                                  @RequestBody(required = false) @Valid ProductCloneRequest request) {
        return productService.cloneProduct(productId, request == null ? new ProductCloneRequest() : request);
    }

    @PutMapping("/{productId}/tags")
    @OperLog(title = "产品管理", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:product:tag")
    public void replaceTags(@PathVariable Long productId, @RequestBody @Valid ProductTagsRequest request) {
        productService.replaceTags(productId, request);
    }

    @GetMapping("/{productId}/properties")
    @SaCheckPermission("iot:product:list")
    public List<TmPropertyVO> listProperties(@PathVariable Long productId) {
        return productService.listProperties(productId);
    }

    @PostMapping("/{productId}/properties")
    @OperLog(title = "物模型属性", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:tm:edit")
    public Long createProperty(@PathVariable Long productId, @RequestBody @Valid TmPropertySaveRequest request) {
        return productService.createProperty(productId, request);
    }

    @PutMapping("/{productId}/properties/{propertyId}")
    @OperLog(title = "物模型属性", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:tm:edit")
    public void updateProperty(@PathVariable Long productId, @PathVariable Long propertyId,
                               @RequestBody @Valid TmPropertySaveRequest request) {
        productService.updateProperty(productId, propertyId, request);
    }

    @DeleteMapping("/{productId}/properties/{propertyId}")
    @OperLog(title = "物模型属性", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:tm:edit")
    public void deleteProperty(@PathVariable Long productId, @PathVariable Long propertyId) {
        productService.deleteProperty(productId, propertyId);
    }

    @GetMapping("/{productId}/events")
    @SaCheckPermission("iot:product:list")
    public List<TmEventVO> listEvents(@PathVariable Long productId) {
        return productService.listEvents(productId);
    }

    @PostMapping("/{productId}/events")
    @OperLog(title = "物模型事件", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:tm:edit")
    public Long createEvent(@PathVariable Long productId, @RequestBody @Valid TmEventSaveRequest request) {
        return productService.createEvent(productId, request);
    }

    @PutMapping("/{productId}/events/{eventId}")
    @OperLog(title = "物模型事件", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:tm:edit")
    public void updateEvent(@PathVariable Long productId, @PathVariable Long eventId,
                            @RequestBody @Valid TmEventSaveRequest request) {
        productService.updateEvent(productId, eventId, request);
    }

    @DeleteMapping("/{productId}/events/{eventId}")
    @OperLog(title = "物模型事件", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:tm:edit")
    public void deleteEvent(@PathVariable Long productId, @PathVariable Long eventId) {
        productService.deleteEvent(productId, eventId);
    }

    @GetMapping("/{productId}/services")
    @SaCheckPermission("iot:product:list")
    public List<TmServiceVO> listServices(@PathVariable Long productId) {
        return productService.listServices(productId);
    }

    @PostMapping("/{productId}/services")
    @OperLog(title = "物模型服务", businessType = BusinessType.INSERT)
    @SaCheckPermission("iot:tm:edit")
    public Long createServiceDef(@PathVariable Long productId, @RequestBody @Valid TmServiceSaveRequest request) {
        return productService.createService(productId, request);
    }

    @PutMapping("/{productId}/services/{serviceId}")
    @OperLog(title = "物模型服务", businessType = BusinessType.UPDATE)
    @SaCheckPermission("iot:tm:edit")
    public void updateServiceDef(@PathVariable Long productId, @PathVariable Long serviceId,
                                 @RequestBody @Valid TmServiceSaveRequest request) {
        productService.updateService(productId, serviceId, request);
    }

    @DeleteMapping("/{productId}/services/{serviceId}")
    @OperLog(title = "物模型服务", businessType = BusinessType.DELETE)
    @SaCheckPermission("iot:tm:edit")
    public void deleteServiceDef(@PathVariable Long productId, @PathVariable Long serviceId) {
        productService.deleteService(productId, serviceId);
    }
}
