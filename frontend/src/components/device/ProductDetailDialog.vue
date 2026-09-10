<template>
  <el-dialog
      v-model="visible"
      title="产品详情"
      width="820px"
      :close-on-click-modal="false"
      destroy-on-close
      class="compact-edit-dialog"
  >
    <el-descriptions
        v-if="product"
        :column="3"
        border
        size="small"
    >
      <el-descriptions-item label="产品名称">
        {{ product.productName }}
      </el-descriptions-item>
      <el-descriptions-item label="产品标识">
        <span class="font-mono">{{ product.productKey }}</span>
      </el-descriptions-item>
      <el-descriptions-item label="节点类型">
        {{ nodeTypeLabel(product.nodeType) }}
      </el-descriptions-item>
      <el-descriptions-item label="厂商">
        {{ product.vendor || '—' }}
      </el-descriptions-item>
      <el-descriptions-item label="型号">
        {{ product.model || '—' }}
      </el-descriptions-item>
    </el-descriptions>
    <el-tabs
        v-model="activeTab"
        class="mt-2 compact-tabs"
    >
      <el-tab-pane
          label="物模型属性"
          name="properties"
      >
        <el-table
            :data="properties"
            border
            stripe
            size="small"
            max-height="300"
        >
          <el-table-column
              prop="identifier"
              label="标识"
          />
          <el-table-column
              prop="propertyName"
              label="名称"
          />
          <el-table-column
              prop="dataType"
              label="数据类型"
          />
          <el-table-column
              prop="unit"
              label="单位"
              width="80"
          />
        </el-table>
      </el-tab-pane>
      <el-tab-pane
          label="事件"
          name="events"
      >
        <el-table
            :data="events"
            border
            stripe
            size="small"
            max-height="300"
        >
          <el-table-column
              prop="identifier"
              label="标识"
          />
          <el-table-column
              prop="eventName"
              label="名称"
          />
          <el-table-column
              prop="eventType"
              label="类型"
              width="100"
          />
        </el-table>
      </el-tab-pane>
      <el-tab-pane
          label="服务"
          name="services"
      >
        <el-table
            :data="services"
            border
            stripe
            size="small"
            max-height="300"
        >
          <el-table-column
              prop="identifier"
              label="标识"
          />
          <el-table-column
              prop="serviceName"
              label="名称"
          />
          <el-table-column
              prop="callType"
              label="调用类型"
              width="100"
          />
        </el-table>
      </el-tab-pane>
      <el-tab-pane
          label="设备表单"
          name="schema"
      >
        <el-input
            :model-value="schemaText"
            type="textarea"
            :rows="12"
            readonly
        />
      </el-tab-pane>
      <el-tab-pane
          label="标签"
          name="tags"
      >
        <div class="flex flex-wrap gap-1">
          <el-tag
              v-for="tag in product?.tags || []"
              :key="tag.tagId"
              :color="tag.color"
          >
            {{ tag.tagKey }}:{{ tag.tagValue }}
          </el-tag>
          <el-empty
              v-if="!product?.tags?.length"
              description="暂无标签"
              :image-size="48"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
    <template #footer>
      <el-button
          size="small"
          @click="visible = false"
      >
        关闭
      </el-button>
      <el-button
          v-if="editable"
          v-hasPermi="['iot:product:edit']"
          type="primary"
          size="small"
          @click="$emit('edit')"
      >
        编辑
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import type {ProductVO, TmEventVO, TmPropertyVO, TmServiceVO} from '@/api/device'

const visible = defineModel<boolean>({required: true})
const props = defineProps<{
  product?: ProductVO;
  properties: TmPropertyVO[];
  events: TmEventVO[];
  services: TmServiceVO[];
  editable: boolean
}>()
defineEmits<{ edit: [] }>()
const activeTab = ref('properties')
const schemaText = computed(() => props.product?.deviceFormSchema ? JSON.stringify(props.product.deviceFormSchema, null, 2) : '未配置设备动态表单')
watch(visible, value => {
  if (value) activeTab.value = 'properties'
})

function nodeTypeLabel(type: number) {
  return ({1: '直连', 2: '网关', 3: '子设备'} as Record<number, string>)[type] || String(type)
}
</script>
