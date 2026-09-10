package com.unisence.iot.admin.device.service;

import com.unisence.iot.admin.device.dto.*;
import com.unisence.iot.admin.device.vo.ProductVO;
import com.unisence.iot.admin.device.vo.TmEventVO;
import com.unisence.iot.admin.device.vo.TmPropertyVO;
import com.unisence.iot.admin.device.vo.TmServiceVO;
import com.unisence.iot.common.api.PageRequest;
import com.unisence.iot.common.api.PageResult;

import java.util.List;

public interface IotProductService {

    PageResult<ProductVO> pageProducts(PageRequest<ProductQuery> request);

    ProductVO getProduct(Long productId);

    Long createProduct(ProductSaveRequest request);

    void updateProduct(Long productId, ProductSaveRequest request);

    void deleteProduct(Long productId);

    void batchDeleteProducts(List<Long> productIds);

    ProductVO cloneProduct(Long productId, ProductCloneRequest request);

    void replaceTags(Long productId, ProductTagsRequest request);

    List<TmPropertyVO> listProperties(Long productId);

    Long createProperty(Long productId, TmPropertySaveRequest request);

    void updateProperty(Long productId, Long propertyId, TmPropertySaveRequest request);

    void deleteProperty(Long productId, Long propertyId);

    List<TmEventVO> listEvents(Long productId);

    Long createEvent(Long productId, TmEventSaveRequest request);

    void updateEvent(Long productId, Long eventId, TmEventSaveRequest request);

    void deleteEvent(Long productId, Long eventId);

    List<TmServiceVO> listServices(Long productId);

    Long createService(Long productId, TmServiceSaveRequest request);

    void updateService(Long productId, Long serviceId, TmServiceSaveRequest request);

    void deleteService(Long productId, Long serviceId);
}
