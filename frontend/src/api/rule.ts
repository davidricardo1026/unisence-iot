import {request} from '@/utils/request'
import type {PageRequest, PageResult} from '@/types/api'
import type {KafkaOutputVO} from '@/api/ruleKafkaOutput'

/**
 * 规则类别。两张规则表各有独立自增主键，`ruleId` 单独无法定位一条规则，
 * 所有读写请求都必须带上它来选路径。
 */
export type RuleKind = 'INSTANT' | 'WINDOW'

export type MessageType = 'device_create' | 'property' | 'event' | 'device_heartbeat' | 'service_heartbeat'
export type WindowType = 'TUMBLING_TIME' | 'HOPPING_TIME'
export type AggregateType = 'COUNT' | 'SUM' | 'MIN' | 'MAX' | 'AVG' | 'FIRST' | 'LAST' | 'CHANGE_RATE'
export type ConditionKind = 'THRESHOLD' | 'SCRIPT'
export type ThresholdOperator = 'GT' | 'GTE' | 'LT' | 'LTE' | 'EQ' | 'NE'
export type ValueSource = 'PROPERTY' | 'EVENT_PARAM'
export type TimeMode = 'PROCESSING_TIME' | 'EVENT_TIME'
export type ErrorPolicy = 'DLQ_MESSAGE' | 'SKIP_RULE' | 'DROP_MESSAGE'
export type EmitMode = 'LEVEL_TRANSITION' | 'EVERY_MATCH'

export interface ValueConfig {
    valueIdentifier?: string
    valueSource?: ValueSource
}

export interface WindowConfig {
    type: WindowType
    timeMode: TimeMode
    sizeMillis?: number
    advanceMillis?: number
    graceMillis?: number
    retentionMillis?: number
    stateScope: 'DEVICE'
}

export interface AggregateConfig {
    type: AggregateType
    valueIdentifier?: string
    valueSource?: ValueSource
}

export interface ThresholdConfig {
    operator?: ThresholdOperator
    threshold?: number
}

export interface RuleLevelVO {
    levelId: number
    levelCode: string
    severity: number
    conditionKind: ConditionKind
    thresholdConfig?: ThresholdConfig
    conditionScript?: string
    outputScript: string
    cooldownMillis?: number
    kafkaOutputIds?: number[]
    kafkaOutputs?: KafkaOutputVO[]
}

export interface RuleVO {
    ruleKind: RuleKind
    ruleId: number
    ruleCode: string
    ruleName: string
    messageType: MessageType
    errorPolicy: ErrorPolicy
    status: 0 | 1
    revision: number
    levelCount: number
    productCount: number
    createTime?: string
    updateTime?: string
    version: number
    /** 即时规则专有；窗口规则为 undefined */
    emitMode?: EmitMode
}

export interface RuleProductVO {
    productId: number
    productKey: string
    productName: string
}

export interface RuleDetailVO extends RuleVO {
    listenerConfig?: { identifiers?: string[] }
    /** 即时规则专有 */
    valueConfig?: ValueConfig
    /** 窗口规则专有 */
    windowConfig?: WindowConfig
    /** 窗口规则专有 */
    aggregateConfig?: AggregateConfig
    filterScript: string
    scriptSha256?: string
    compileResult?: Record<string, unknown>
    levels: RuleLevelVO[]
    products: RuleProductVO[]
}

export interface RuleLevelRequest {
    levelCode: string
    severity: number
    conditionKind: ConditionKind
    thresholdConfig?: ThresholdConfig
    conditionScript?: string
    outputScript: string
    cooldownMillis?: number
    kafkaOutputIds: number[]
}

interface RuleSaveRequestBase {
    ruleCode?: string
    ruleName: string
    messageType: MessageType
    productIds: number[]
    listenerConfig?: { identifiers: string[] }
    filterScript?: string
    levels: RuleLevelRequest[]
    errorPolicy: ErrorPolicy
    version?: number
}

export interface InstantRuleSaveRequest extends RuleSaveRequestBase {
    valueConfig?: ValueConfig
    emitMode: EmitMode
}

export interface WindowRuleSaveRequest extends RuleSaveRequestBase {
    windowConfig: WindowConfig
    aggregateConfig: AggregateConfig
}

/**
 * 与后端两组路径一一对应：`INSTANT` 的请求体带 `valueConfig`，
 * `WINDOW` 的带 `windowConfig` + `aggregateConfig`，两者互不相交。
 */
export type RuleSaveRequestOf<K extends RuleKind> =
    K extends 'INSTANT' ? InstantRuleSaveRequest : WindowRuleSaveRequest

export interface RuleQuery {
    keyword?: string
    messageType?: MessageType
    status?: number
    productId?: number
}

export interface RuleCapabilities {
    instantRule: { configure: boolean; activate: boolean; runtimeInstalled: boolean }
    windowRule: { configure: boolean; activate: boolean; runtimeInstalled: boolean }
}

export function getRuleCapabilities() {
    return request<RuleCapabilities>({url: '/iot/rules/capabilities', method: 'get'})
}

function segment(kind: RuleKind): string {
    return kind === 'INSTANT' ? 'instant' : 'window'
}

export function listRules(kind: RuleKind, params: PageRequest<RuleQuery>) {
    return request<PageResult<RuleVO>>({url: `/iot/rules/${segment(kind)}`, method: 'get', params})
}

export function getRule(kind: RuleKind, ruleId: number) {
    return request<RuleDetailVO>({url: `/iot/rules/${segment(kind)}/${ruleId}`, method: 'get'})
}

export function createRule<K extends RuleKind>(kind: K, data: RuleSaveRequestOf<K>) {
    return request<number>({url: `/iot/rules/${segment(kind)}`, method: 'post', data})
}

export function updateRule<K extends RuleKind>(kind: K, ruleId: number, data: RuleSaveRequestOf<K>) {
    return request<void>({url: `/iot/rules/${segment(kind)}/${ruleId}`, method: 'put', data})
}

export function deleteRule(kind: RuleKind, ruleId: number) {
    return request<void>({url: `/iot/rules/${segment(kind)}/${ruleId}`, method: 'delete'})
}

export function changeRuleStatus(kind: RuleKind, ruleId: number, status: 0 | 1, version: number) {
    return request<void>({url: `/iot/rules/${segment(kind)}/${ruleId}/status`, method: 'put', data: {status, version}})
}

export function listRuleProducts(kind: RuleKind, ruleId: number) {
    return request<RuleProductVO[]>({url: `/iot/rules/${segment(kind)}/${ruleId}/products`, method: 'get'})
}

export function validateRule<K extends RuleKind>(kind: K, data: RuleSaveRequestOf<K>) {
    return request<Record<string, unknown>>({url: `/iot/rules/${segment(kind)}/validate`, method: 'post', data})
}
