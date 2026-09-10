<script setup lang="ts">
import {ElMessage} from 'element-plus'

const form = reactive({
  platformName: '统一感知',
  apiBase: import.meta.env.VITE_API_BASE_URL,
  refreshInterval: 30,
})

function handleSave() {
  ElMessage.success('配置已保存（演示）')
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <span class="font-medium">系统参数</span>
    </template>
    <el-form
        :model="form"
        label-width="120px"
        class="max-w-lg"
    >
      <el-form-item label="平台名称">
        <el-input v-model="form.platformName"/>
      </el-form-item>
      <el-form-item label="API 地址">
        <el-input v-model="form.apiBase"/>
      </el-form-item>
      <el-form-item label="刷新间隔(秒)">
        <el-input-number
            v-model="form.refreshInterval"
            :min="5"
            :max="300"
        />
      </el-form-item>
      <el-form-item>
        <el-button
            type="primary"
            @click="handleSave"
        >
          <el-icon class="mr-1">
            <Check/>
          </el-icon>
          保存
        </el-button>
      </el-form-item>
    </el-form>
  </el-card>
</template>
