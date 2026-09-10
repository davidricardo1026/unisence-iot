import {request} from '@/utils/request'
import type {PageRequest, PageResult} from '@/types/api'

// ========== 标签 ==========
export interface TagVO {
    tagId: number
    tagKey: string
    tagValue: string
    color?: string
    description?: string
    version?: number
    createTime?: string
}

export interface TagQuery {
    tagKey?: string
    tagValue?: string
}

export interface TagSaveRequest {
    tagKey: string
    tagValue: string
    color?: string
    description?: string
    version?: number
}

export type TagUpdateRequest = Omit<TagSaveRequest, 'tagKey'>

export function listTags(params: PageRequest<TagQuery>) {
    return request<PageResult<TagVO>>({url: '/iot/tags', method: 'get', params})
}

export function createTag(data: TagSaveRequest) {
    return request<number>({url: '/iot/tags', method: 'post', data})
}

export function updateTag(tagId: number, data: TagUpdateRequest) {
    return request<void>({url: `/iot/tags/${tagId}`, method: 'put', data})
}

export function deleteTag(tagId: number) {
    return request<void>({url: `/iot/tags/${tagId}`, method: 'delete'})
}

// ========== 产品 ==========
export interface ProductVO {
    productId: number
    productKey: string
    productName: string
    nodeType: number
    netType?: number
    vendor?: string
    model?: string
    icon?: string
    iconUrl?: string
    description?: string
    attributes?: Record<string, unknown>
    deviceFormSchema?: Record<string, unknown>
    productType: number
    version?: number
    createTime?: string
    tags?: TagVO[]
}

export interface ProductQuery {
    keyword?: string
    productName?: string
    productKey?: string
    productType?: number
    tagId?: number
    nodeType?: number
}

export interface ProductSaveRequest {
    productName: string
    nodeType: number
    netType?: number
    vendor?: string
    model?: string
    icon?: string
    iconUrl?: string
    description?: string
    attributes?: Record<string, unknown>
    deviceFormSchema?: Record<string, unknown>
    productType?: number
    version?: number
}

export function listProducts(params: PageRequest<ProductQuery>) {
    return request<PageResult<ProductVO>>({url: '/iot/products', method: 'get', params})
}

export function getProduct(productId: number) {
    return request<ProductVO>({url: `/iot/products/${productId}`, method: 'get'})
}

export function createProduct(data: ProductSaveRequest) {
    return request<number>({url: '/iot/products', method: 'post', data})
}

export function updateProduct(productId: number, data: ProductSaveRequest) {
    return request<void>({url: `/iot/products/${productId}`, method: 'put', data})
}

export function deleteProduct(productId: number) {
    return request<void>({url: `/iot/products/${productId}`, method: 'delete'})
}

export function batchDeleteProducts(productIds: number[]) {
    return request<void>({url: '/iot/products/batch', method: 'delete', data: productIds})
}

export function cloneProduct(productId: number, data?: { productName?: string; keyStrategy?: 'inherit' | 'random' }) {
    return request<ProductVO>({url: `/iot/products/${productId}/clone`, method: 'post', data: data || {}})
}

export function replaceProductTags(productId: number, tagIds: number[]) {
    return request<void>({url: `/iot/products/${productId}/tags`, method: 'put', data: {tagIds}})
}

export * from './device/thing-model'

// ========== 设备 ==========
export interface DeviceVO {
    deviceId: number
    productId: number
    productKey?: string
    productName?: string
    icon?: string
    iconUrl?: string
    deviceCode: string
    deviceName?: string
    gatewayId?: number
    nodeType: number
    status: number
    lastOnlineAt?: string
    activatedAt?: string
    longitude?: number
    latitude?: number
    address?: string
    deviceFormData?: Record<string, unknown>
    version?: number
    createTime?: string
}

export interface LatestPropertySnapshotVO {
    available: boolean
    properties: Array<{
        identifier: string
        value: unknown
        valueType: number
        occurredAt: number
        msgId: string
    }>
}

export interface PropertyHistoryVO {
    identifier: string
    dataType: string
    unit?: string
    points: Array<{
        occurredAt: number
        value: number
    }>
}

export interface PropertyRawValueVO {
    occurredAt: number
    value: unknown
    valueType: number
    msgId?: string
}

export interface PropertyRawQuery {
    from: number
    to: number
}

export interface DeviceEventVO {
    occurredAt: number
    identifier: string
    eventName: string
    eventType: number
    params: Record<string, unknown>
    msgId: string
}

export interface DeviceEventQuery {
    identifier: string
    from: number
    to: number
}

export interface DeviceOnlineLogVO {
    occurredAt: number
    event: number
    reason: string
}

export interface DeviceOnlineLogQuery {
    from: number
    to: number
}

export interface DeviceOnlineHistoryVO {
    initialEvent?: number | null
    points: DeviceOnlineLogVO[]
}

export interface DeviceQuery {
    productId?: number
    deviceCode?: string
    deviceName?: string
    status?: number
    formFilters?: Record<string, DeviceFormFilter>
}

export interface DeviceFormFilter {
    value?: string
    operator?: 'GT' | 'GE' | 'EQ' | 'NE' | 'LE' | 'LT'
    number?: number
}

export interface DeviceSaveRequest {
    productId: number
    deviceCode?: string
    deviceName?: string
    nodeType?: number
    gatewayId?: number
    longitude?: number
    latitude?: number
    address?: string
    deviceFormData?: Record<string, unknown>
    version?: number
}

export function listDevices(params: PageRequest<DeviceQuery>) {
    return request<PageResult<DeviceVO>>({url: '/iot/devices', method: 'get', params})
}

export function getDevice(deviceId: number) {
    return request<DeviceVO>({url: `/iot/devices/${deviceId}`, method: 'get'})
}

export function getDeviceLatestProperties(deviceId: number) {
    return request<LatestPropertySnapshotVO>({url: `/iot/devices/${deviceId}/latest-properties`, method: 'get'})
}

export function getDevicePropertyHistory(deviceId: number, identifier: string, from: number, to: number) {
    return request<PropertyHistoryVO>({
        url: `/iot/devices/${deviceId}/properties/${encodeURIComponent(identifier)}/history`,
        method: 'get',
        params: {from, to},
    })
}

export function listDevicePropertyRawValues(deviceId: number,
                                            identifier: string,
                                            params: PageRequest<PropertyRawQuery>) {
    return request<PageResult<PropertyRawValueVO>>({
        url: `/iot/devices/${deviceId}/properties/${encodeURIComponent(identifier)}/values`,
        method: 'get',
        params,
    })
}

export function listDeviceEvents(deviceId: number, params: PageRequest<DeviceEventQuery>) {
    return request<PageResult<DeviceEventVO>>({
        url: `/iot/devices/${deviceId}/events`,
        method: 'get',
        params,
    })
}

export function listDeviceOnlineLogs(deviceId: number, params: PageRequest<DeviceOnlineLogQuery>) {
    return request<PageResult<DeviceOnlineLogVO>>({
        url: `/iot/devices/${deviceId}/online-logs`,
        method: 'get',
        params,
    })
}

export function getDeviceOnlineHistory(deviceId: number, from: number, to: number) {
    return request<DeviceOnlineHistoryVO>({
        url: `/iot/devices/${deviceId}/online-history`,
        method: 'get',
        params: {from, to},
    })
}

export function createDevice(data: DeviceSaveRequest) {
    return request<DeviceVO>({url: '/iot/devices', method: 'post', data})
}

export function updateDevice(deviceId: number, data: DeviceSaveRequest) {
    return request<void>({url: `/iot/devices/${deviceId}`, method: 'put', data})
}

export function deleteDevice(deviceId: number) {
    return request<void>({url: `/iot/devices/${deviceId}`, method: 'delete'})
}
