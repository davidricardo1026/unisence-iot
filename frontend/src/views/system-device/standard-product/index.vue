<template>
  <ProductCardPage ref="productPageRef" :mode="mode"/>
</template>

<script setup lang="ts">
import ProductCardPage from '@/components/device/ProductCardPage.vue'

withDefaults(defineProps<{
  mode?: 'standard' | 'product'
}>(), {
  mode: 'standard'
})

const productPageRef = ref<InstanceType<typeof ProductCardPage>>()

// 两个库页均被 keep-alive 缓存；返回页面时刷新列表，避免保留旧数据。
onActivated(() => productPageRef.value?.refresh())
</script>
