<template>
  <div ref="chartElement" class="h-[280px] w-full" role="img" :aria-label="`${name}属性历史曲线`"/>
</template>

<script setup lang="ts">
import {LineChart} from 'echarts/charts'
import {DataZoomComponent, GridComponent, TooltipComponent} from 'echarts/components'
import {type ECharts, init, use} from 'echarts/core'
import {CanvasRenderer} from 'echarts/renderers'
import type {PropertyHistoryVO} from '@/api/device'

use([LineChart, GridComponent, TooltipComponent, DataZoomComponent, CanvasRenderer])

const props = defineProps<{
  name: string
  history?: PropertyHistoryVO
}>()

const chartElement = ref<HTMLDivElement>()
let chart: ECharts | undefined
let resizeObserver: ResizeObserver | undefined

function render() {
  if (!chart) return
  const points = props.history?.points || []
  const unit = props.history?.unit ? ` ${props.history.unit}` : ''
  chart.setOption({
    animation: points.length < 300,
    grid: {left: 52, right: 18, top: 18, bottom: 54, containLabel: false},
    tooltip: {
      trigger: 'axis',
      formatter: (items: unknown) => {
        const item = Array.isArray(items) ? items[0] as { value?: [number, number] } : undefined
        const value = item?.value
        if (!value) return ''
        const displayValue = props.history?.dataType === 'bool' ? (value[1] === 1 ? 'true' : 'false') : value[1]
        return `${new Date(value[0]).toLocaleString()}<br/>${props.name}：${displayValue}${unit}`
      },
    },
    xAxis: {type: 'time', axisLabel: {fontSize: 10}, splitLine: {show: false}},
    yAxis: {
      type: 'value',
      scale: true,
      name: props.history?.unit || '',
      nameTextStyle: {fontSize: 10},
      axisLabel: {fontSize: 10},
      splitLine: {lineStyle: {color: '#eef0f3'}},
    },
    dataZoom: [
      {type: 'inside', filterMode: 'none'},
      {type: 'slider', height: 18, bottom: 8, borderColor: '#dcdfe6', fillerColor: 'rgba(64,158,255,.14)'},
    ],
    series: [{
      name: props.name,
      type: 'line',
      showSymbol: points.length < 80,
      symbolSize: 4,
      sampling: 'lttb',
      step: props.history?.dataType === 'bool' ? 'end' : false,
      lineStyle: {width: 1.5},
      areaStyle: {opacity: 0.05},
      data: points.map(point => [point.occurredAt, point.value]),
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

watch(() => [props.name, props.history] as const, render, {deep: true})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  chart?.dispose()
})
</script>
