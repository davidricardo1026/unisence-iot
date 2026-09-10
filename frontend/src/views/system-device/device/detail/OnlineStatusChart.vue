<template>
  <div ref="chartElement" class="h-[230px] w-full" role="img" aria-label="设备在线状态历史阶梯图"/>
</template>

<script setup lang="ts">
import {LineChart} from 'echarts/charts'
import {DataZoomComponent, GridComponent, TooltipComponent} from 'echarts/components'
import {type ECharts, init, use} from 'echarts/core'
import {CanvasRenderer} from 'echarts/renderers'
import type {DeviceOnlineHistoryVO} from '@/api/device'

use([LineChart, GridComponent, TooltipComponent, DataZoomComponent, CanvasRenderer])

const props = defineProps<{
  history?: DeviceOnlineHistoryVO
  from: number
  to: number
}>()

const chartElement = ref<HTMLDivElement>()
let chart: ECharts | undefined
let resizeObserver: ResizeObserver | undefined

function statusValue(event: number) {
  return event === 1 ? 1 : 0
}

function statusLabel(value: number) {
  return value === 1 ? '在线' : '离线'
}

function reasonLabel(reason?: string) {
  if (!reason) return ''
  return ({
    connect: '设备连接',
    heartbeat_timeout: '心跳超时',
    lwt: '遗嘱离线',
    disconnect: '主动断开',
  } as Record<string, string>)[reason] || '其他'
}

function render() {
  if (!chart) return
  const transitions = [...(props.history?.points || [])].sort((left, right) => left.occurredAt - right.occurredAt)
  const data: Array<{
    value: [number, number]
    reason?: string
    itemStyle: { color: string }
  }> = []
  let currentEvent = props.history?.initialEvent ?? undefined
  if (currentEvent && transitions[0]?.occurredAt !== props.from) {
    data.push({
      value: [props.from, statusValue(currentEvent)],
      itemStyle: {color: currentEvent === 1 ? '#67c23a' : '#f56c6c'},
    })
  }
  for (const point of transitions) {
    currentEvent = point.event
    data.push({
      value: [point.occurredAt, statusValue(point.event)],
      reason: point.reason,
      itemStyle: {color: point.event === 1 ? '#67c23a' : '#f56c6c'},
    })
  }
  if (currentEvent && data.length && data[data.length - 1].value[0] < props.to) {
    data.push({
      value: [props.to, statusValue(currentEvent)],
      itemStyle: {color: currentEvent === 1 ? '#67c23a' : '#f56c6c'},
    })
  }

  chart.setOption({
    animation: data.length < 300,
    grid: {left: 54, right: 18, top: 18, bottom: 50},
    tooltip: {
      trigger: 'item',
      formatter: (item: unknown) => {
        const chartItem = item as { data?: { value?: [number, number]; reason?: string } }
        const value = chartItem.data?.value
        if (!value) return ''
        const reason = chartItem.data?.reason ? `<br/>原因：${reasonLabel(chartItem.data.reason)}` : ''
        return `${new Date(value[0]).toLocaleString()}<br/>状态：${statusLabel(value[1])}${reason}`
      },
    },
    xAxis: {
      type: 'time',
      min: props.from,
      max: props.to,
      axisLabel: {fontSize: 10},
      splitLine: {show: false},
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 1,
      interval: 1,
      axisLabel: {fontSize: 10, formatter: (value: number) => statusLabel(value)},
      splitLine: {lineStyle: {color: '#eef0f3'}},
    },
    dataZoom: [
      {type: 'inside', filterMode: 'none'},
      {type: 'slider', height: 16, bottom: 7, borderColor: '#dcdfe6', fillerColor: 'rgba(64,158,255,.14)'},
    ],
    series: [{
      type: 'line',
      step: 'end',
      showSymbol: true,
      symbolSize: 7,
      connectNulls: false,
      lineStyle: {width: 2, color: '#409eff'},
      data,
    }],
  }, true)
}

onMounted(() => {
  if (!chartElement.value) return
  chart = init(chartElement.value)
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(chartElement.value)
  render()
})

watch(() => [props.history, props.from, props.to] as const, render, {deep: true})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
})
</script>
