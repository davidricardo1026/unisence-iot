import {request} from '@/utils/request'

export interface TmPropertyVO {
    propertyId: number;
    productId: number;
    identifier: string;
    propertyName: string;
    dataType: string;
    accessMode?: number;
    unit?: string;
    retentionDays: number;
    version?: number
}

export interface TmEventVO {
    eventId: number;
    productId: number;
    identifier: string;
    eventName: string;
    eventType?: number;
    inputParams?: Record<string, unknown>[];
    ttlEnabled: boolean;
    ttlValue?: number;
    ttlUnit?: 'h' | 'd';
    version?: number
}

export interface TmServiceVO {
    serviceId: number;
    productId: number;
    identifier: string;
    serviceName: string;
    inputParams?: Record<string, unknown>[];
    outputParams?: Record<string, unknown>[];
    callType?: number;
    version?: number
}

export const listTmProperties = (productId: number) => request<TmPropertyVO[]>({
    url: `/iot/products/${productId}/properties`,
    method: 'get'
})
export const createTmProperty = (productId: number, data: Partial<TmPropertyVO> & {
    identifier: string;
    propertyName: string;
    dataType: string;
    retentionDays: number
}) => request<number>({url: `/iot/products/${productId}/properties`, method: 'post', data})
export const updateTmProperty = (productId: number, propertyId: number, data: Record<string, unknown>) => request<void>({
    url: `/iot/products/${productId}/properties/${propertyId}`,
    method: 'put',
    data
})
export const deleteTmProperty = (productId: number, propertyId: number) => request<void>({
    url: `/iot/products/${productId}/properties/${propertyId}`,
    method: 'delete'
})
export const listTmEvents = (productId: number) => request<TmEventVO[]>({
    url: `/iot/products/${productId}/events`,
    method: 'get'
})
export const createTmEvent = (productId: number, data: {
    identifier: string;
    eventName: string;
    ttlEnabled: boolean;
    ttlValue?: number;
    ttlUnit?: 'h' | 'd';
    eventType?: number;
    inputParams?: unknown
}) => request<number>({url: `/iot/products/${productId}/events`, method: 'post', data})
export const updateTmEvent = (productId: number, eventId: number, data: Record<string, unknown>) => request<void>({
    url: `/iot/products/${productId}/events/${eventId}`,
    method: 'put',
    data
})
export const deleteTmEvent = (productId: number, eventId: number) => request<void>({
    url: `/iot/products/${productId}/events/${eventId}`,
    method: 'delete'
})
export const listTmServices = (productId: number) => request<TmServiceVO[]>({
    url: `/iot/products/${productId}/services`,
    method: 'get'
})
export const createTmService = (productId: number, data: {
    identifier: string;
    serviceName: string;
    callType?: number;
    inputParams?: unknown;
    outputParams?: unknown
}) => request<number>({url: `/iot/products/${productId}/services`, method: 'post', data})
export const updateTmService = (productId: number, serviceId: number, data: Record<string, unknown>) => request<void>({
    url: `/iot/products/${productId}/services/${serviceId}`,
    method: 'put',
    data
})
export const deleteTmService = (productId: number, serviceId: number) => request<void>({
    url: `/iot/products/${productId}/services/${serviceId}`,
    method: 'delete'
})
