import {request} from '@/utils/request'
import type {PageRequest, PageResult} from '@/types/api'

export type KafkaOutputPurpose = 'RULE_OUTPUT' | 'ROUTE'
export type OutputFormat = 'JSON' | 'MESSAGEPACK'

export interface KafkaOutputVO {
    outputId: number
    outputCode: string
    outputName: string
    purpose: KafkaOutputPurpose
    targetTopic: string
    format: OutputFormat
    referenceCount: number
    createTime?: string
    updateTime?: string
    version: number
}

export interface KafkaOutputSaveRequest {
    outputCode: string
    outputName: string
    purpose: KafkaOutputPurpose
    targetTopic: string
    format: OutputFormat
    version?: number
}

export interface KafkaOutputQuery {
    purpose?: KafkaOutputPurpose
    keyword?: string
}

export function listKafkaOutputs(params: PageRequest<KafkaOutputQuery>) {
    return request<PageResult<KafkaOutputVO>>({
        url: '/iot/rule-kafka-outputs',
        method: 'get',
        params,
    })
}

export function getKafkaOutput(outputId: number) {
    return request<KafkaOutputVO>({
        url: `/iot/rule-kafka-outputs/${outputId}`,
        method: 'get',
    })
}

export function createKafkaOutput(data: KafkaOutputSaveRequest) {
    return request<number>({
        url: '/iot/rule-kafka-outputs',
        method: 'post',
        data,
    })
}

export function updateKafkaOutput(outputId: number, data: KafkaOutputSaveRequest) {
    return request<void>({
        url: `/iot/rule-kafka-outputs/${outputId}`,
        method: 'put',
        data,
    })
}

export function deleteKafkaOutput(outputId: number) {
    return request<void>({
        url: `/iot/rule-kafka-outputs/${outputId}`,
        method: 'delete',
    })
}
