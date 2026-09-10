<script setup lang="ts">
/**
 * 首页 Dashboard
 * 目前显示为空白页，后续将设计为 BI、报表等可视化展示大屏
 */
</script>

<template>
  <div class="h-full w-full flex items-center justify-center bg-gray-50/30">
    <div class="text-center">
      <el-empty
          description="首页内容规划中（BI、报表大屏）"
          :image-size="200"
      >
        <template #image>
          <el-icon
              :size="80"
              class="text-gray-200"
          >
            <DataAnalysis/>
          </el-icon>
        </template>
      </el-empty>
    </div>
  </div>
</template>

<style scoped>
</style>
