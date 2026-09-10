<template>
  <div class="flex min-h-0 flex-1 flex-col overflow-hidden bg-white p-3">
    <div class="compact-query-form mb-2 shrink-0">
      <span class="text-[11px] text-gray-500">
        设计变量、Element Plus 主题和业务页公共类的集中回归页
      </span>
      <div class="ml-auto flex gap-2">
        <el-button @click="showMessage('info')">
          消息提示
        </el-button>
        <el-button
            type="primary"
            @click="dialogVisible = true"
        >
          打开弹窗
        </el-button>
      </div>
    </div>

    <el-tabs
        v-model="activeTab"
        class="min-h-0 flex-1 compact-tabs"
    >
      <el-tab-pane
          label="按钮与状态"
          name="actions"
      >
        <section class="ui-preview-section">
          <h2 class="ui-preview-title">
            语义按钮
          </h2>
          <div class="flex flex-wrap gap-2">
            <el-button>默认</el-button>
            <el-button type="primary">
              主操作
            </el-button>
            <el-button type="success">
              成功
            </el-button>
            <el-button type="warning">
              警告
            </el-button>
            <el-button type="danger">
              危险
            </el-button>
            <el-button disabled>
              禁用
            </el-button>
            <el-button
                type="primary"
                loading
            >
              提交中
            </el-button>
            <el-button
                type="primary"
                link
            >
              链接操作
            </el-button>
          </div>
        </section>

        <section class="ui-preview-section">
          <h2 class="ui-preview-title">
            反馈状态
          </h2>
          <div class="grid gap-2 md:grid-cols-2">
            <el-alert
                title="操作成功"
                type="success"
                show-icon
                :closable="false"
            />
            <el-alert
                title="请检查表单内容"
                type="warning"
                show-icon
                :closable="false"
            />
            <el-alert
                title="数据加载失败"
                type="error"
                show-icon
                :closable="false"
            />
            <el-alert
                title="这是一条辅助信息"
                type="info"
                show-icon
                :closable="false"
            />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane
          label="表单与弹窗"
          name="forms"
      >
        <section class="ui-preview-section max-w-3xl">
          <h2 class="ui-preview-title">
            紧凑表单
          </h2>
          <el-form
              class="compact-edit-form"
              label-width="76px"
              :model="form"
              :rules="previewRules"
          >
            <el-form-item label="名称">
              <el-input
                  v-model="form.name"
                  placeholder="请输入名称"
              />
            </el-form-item>
            <el-form-item
                label="校验示例"
                prop="validationExample"
                error="该字段填写有误，请检查输入内容后重新提交；较长的错误信息会使用整行宽度自动换行"
            >
              <el-input
                  v-model="form.validationExample"
                  placeholder="错误提示完整占用下一行"
              />
            </el-form-item>
            <el-form-item label="类型">
              <el-select
                  v-model="form.type"
                  placeholder="请选择"
              >
                <el-option
                    label="标准类型"
                    value="standard"
                />
                <el-option
                    label="自定义类型"
                    value="custom"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="状态">
              <el-radio-group v-model="form.enabled">
                <el-radio :value="true">
                  启用
                </el-radio>
                <el-radio :value="false">
                  停用
                </el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="备注">
              <el-input
                  v-model="form.remark"
                  type="textarea"
                  :rows="3"
              />
            </el-form-item>
          </el-form>
        </section>
      </el-tab-pane>

      <el-tab-pane
          label="表格与空状态"
          name="table"
      >
        <section class="flex h-full min-h-0 flex-col">
          <div class="compact-action-toolbar">
            <div class="flex gap-2">
              <el-button type="primary">
                <el-icon>
                  <Plus/>
                </el-icon>
                新增
              </el-button>
              <el-button
                  type="danger"
                  plain
                  disabled
              >
                <el-icon>
                  <Delete/>
                </el-icon>
                批量删除
              </el-button>
            </div>
            <span class="text-[11px] text-gray-400">表头固定，数据区内部滚动</span>
          </div>
          <div class="compact-table-region">
            <el-table
                :data="rows"
                border
                stripe
                height="100%"
                row-key="id"
            >
              <el-table-column
                  prop="name"
                  label="名称"
                  min-width="140"
              />
              <el-table-column
                  prop="type"
                  label="类型"
                  width="120"
              />
              <el-table-column
                  label="状态"
                  width="90"
                  align="center"
              >
                <template #default="{row}">
                  <el-tag :type="row.enabled ? 'success' : 'info'">
                    {{ row.enabled ? '启用' : '停用' }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                  label="操作"
                  width="80"
                  fixed="right"
                  align="center"
              >
                <template #default>
                  <el-tooltip
                      content="编辑"
                      placement="top"
                      :enterable="false"
                  >
                    <el-button
                        type="primary"
                        link
                    >
                      <el-icon>
                        <Edit/>
                      </el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip
                      content="删除"
                      placement="top"
                      :enterable="false"
                  >
                    <el-button
                        type="danger"
                        link
                    >
                      <el-icon>
                        <Delete/>
                      </el-icon>
                    </el-button>
                  </el-tooltip>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="compact-pagination">
            <el-pagination
                :current-page="1"
                :page-size="PAGE_DEFAULT.PAGE_SIZE"
                :page-sizes="PAGE_DEFAULT.PAGE_SIZES"
                :total="rows.length"
                layout="total, sizes, prev, pager, next"
            />
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane
          label="Loading / Empty / Error"
          name="states"
      >
        <div class="grid h-full min-h-0 gap-3 md:grid-cols-3">
          <section
              v-loading="true"
              class="ui-preview-state"
              element-loading-text="加载中"
          >
            <span>加载状态</span>
          </section>
          <section class="ui-preview-state">
            <el-empty
                description="暂无数据"
                :image-size="64"
            />
          </section>
          <section class="ui-preview-state">
            <el-result
                icon="error"
                title="加载失败"
                sub-title="请检查网络后重试"
            >
              <template #extra>
                <el-button type="primary">
                  重试
                </el-button>
              </template>
            </el-result>
          </section>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
        v-model="dialogVisible"
        title="标准编辑弹窗"
        width="480px"
        class="compact-edit-dialog"
        :close-on-click-modal="false"
    >
      <el-form
          class="compact-edit-form"
          label-width="76px"
          :model="form"
      >
        <el-form-item label="名称">
          <el-input v-model="form.name"/>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
              v-model="form.remark"
              type="textarea"
              :rows="3"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">
          取消
        </el-button>
        <el-button
            type="primary"
            @click="dialogVisible = false"
        >
          确定
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {ElMessage} from 'element-plus'
import {PAGE_DEFAULT} from '@/types/api'

interface PreviewRow {
  id: number
  name: string
  type: string
  enabled: boolean
}

const activeTab = ref('actions')
const dialogVisible = ref(false)
const form = reactive({
  name: '示例设备',
  validationExample: '',
  type: 'standard',
  enabled: true,
  remark: '',
})
const previewRules = {}
const rows: PreviewRow[] = [
  {id: 1, name: '温度传感器', type: '传感器', enabled: true},
  {id: 2, name: '压力变送器', type: '变送器', enabled: true},
  {id: 3, name: '振动监测器', type: '监测器', enabled: false},
]

function showMessage(type: 'info') {
  ElMessage({type, message: '这是统一的消息提示样式'})
}
</script>

<style scoped>
.ui-preview-section {
  margin-bottom: var(--ui-space-3);
  padding: var(--ui-space-3);
  border: 1px solid var(--ui-border-light);
  border-radius: var(--ui-radius-md);
}

.ui-preview-title {
  margin: 0 0 var(--ui-space-3);
  color: var(--ui-text-primary);
  font-size: var(--ui-font-sm);
  font-weight: 600;
}

.ui-preview-state {
  display: flex;
  min-height: 220px;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ui-border-light);
  border-radius: var(--ui-radius-md);
}
</style>
