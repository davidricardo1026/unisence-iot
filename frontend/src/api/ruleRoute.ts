import {request} from '@/utils/request'
import type {PageRequest, PageResult} from '@/types/api'
import type {KafkaOutputVO} from '@/api/ruleKafkaOutput'
import type {RuleProductVO} from '@/api/rule'

export type RouteMessageType = 'property' | 'event'

export interface RuleRouteVO {
    ruleId: number
    ruleCode: string
    ruleName: string
    messageType: RouteMessageType
    status: 0 | 1
    productCount: number
    targetTopics: string[]
    createTime?: string
    updateTime?: string
    version: number
}

export interface RuleRouteDetailVO extends RuleRouteVO {
    products: RuleProductVO[]
    kafkaOutputs: KafkaOutputVO[]
}

export interface RuleRouteSaveRequest {
    ruleCode: string
    ruleName: string
    messageType: RouteMessageType
    productIds: number[]
    kafkaOutputIds: number[]
    version?: number
}

export interface RuleRouteQuery {
    keyword?: string
    productId?: number
    messageType?: RouteMessageType
    status?: number
}

export interface RuleRouteStatusRequest {
    status: 0 | 1
    version: number
}

export function listRuleRoutes(params: PageRequest<RuleRouteQuery>) {
    return request<PageResult<RuleRouteVO>>({
        url: '/iot/rule-routes',
        method: 'get',
        params,
    })
}

export function getRuleRoute(ruleId: number) {
    return request<RuleRouteDetailVO>({
        url: `/iot/rule-routes/${ruleId}`,
        method: 'get',
    })
}

export function createRuleRoute(data: RuleRouteSaveRequest) {
    return request<number>({
        url: '/iot/rule-routes',
        method: 'post',
        data,
    })
}

export function updateRuleRoute(ruleId: number, data: RuleRouteSaveRequest) {
    return request<void>({
        url: `/iot/rule-routes/${ruleId}`,
        method: 'put',
        data,
    })
}

export function deleteRuleRoute(ruleId: number) {
    return request<void>({
        url: `/iot/rule-routes/${ruleId}`,
        method: 'delete',
    })
}

export function updateRuleRouteStatus(ruleId: number, data: RuleRouteStatusRequest) {
    return request<void>({
        url: `/iot/rule-routes/${ruleId}/status`,
        method: 'put',
        data,
    })
}
