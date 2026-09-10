<template>
  <div class="flex min-h-0 flex-1 flex-col bg-white p-2">
    <div class="mb-1 flex shrink-0 items-center justify-between border-b border-gray-200 pb-1">
      <div class="flex min-w-0 items-center gap-2">
        <el-tooltip content="返回产品库" placement="bottom" :enterable="false">
          <el-button text size="small" class="!p-1" aria-label="返回产品库" @click="goBack">
            <el-icon>
              <ArrowLeft/>
            </el-icon>
          </el-button>
        </el-tooltip>
        <h1 class="truncate text-[13px] font-semibold text-gray-800">
          {{ isNew ? `新建${productLabel}` : product.productName || `${productLabel}建模` }}
        </h1>
        <span class="truncate text-[11px] text-gray-400">
          {{ isNew ? '填写产品与接入信息后继续建模' : `产品标识：${product.productKey}` }}
        </span>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <span v-if="!isNew" class="text-[11px] text-gray-500">完成度 {{ completion }}%</span>
        <el-button v-if="!isNew && !editing" type="primary" size="small" @click="startEditing">
          编辑
        </el-button>
        <el-button v-if="isNew || editing" type="primary" size="small" :loading="saving" @click="saveProduct">
          {{ isNew ? '创建并继续建模' : '保存基础配置' }}
        </el-button>
      </div>
    </div>

    <el-alert
        v-if="isNew"
        class="mb-2 shrink-0"
        type="info"
        :closable="false"
        :title="`${productLabel}的物模型、设备模板与标签将在创建成功后按独立资源保存，避免出现半成功配置。`"
    />

    <el-tabs v-model="activeTab" class="workbench-tabs flex min-h-0 flex-1 flex-col compact-tabs">
      <el-tab-pane label="概览与接入" name="overview" class="min-h-0 overflow-auto">
        <div class="w-full py-2">
          <el-alert v-if="!isNew && !editing" class="mb-2" type="info" :closable="false"
                    :title="`当前为查看模式；点击右上角“编辑”后才可修改${productLabel}。`"/>
          <el-form ref="formRef" :model="product" :rules="productRules" :disabled="isReadonly" label-width="76px"
                   size="small">
            <div class="mb-2 text-[12px] font-medium text-gray-700">产品身份</div>
            <div class="grid grid-cols-1 gap-x-4 md:grid-cols-2">
              <el-form-item label="产品名称" prop="productName">
                <el-input v-model="product.productName" maxlength="100" show-word-limit placeholder="如：智能温控器"/>
              </el-form-item>
              <el-form-item label="节点类型">
                <el-select v-model="product.nodeType" class="!w-full">
                  <el-option label="直连设备" :value="1"/>
                  <el-option label="网关" :value="2"/>
                  <el-option label="子设备" :value="3"/>
                </el-select>
                <div class="mt-1 text-[11px] leading-none text-gray-400">默认直连设备，按实际接入形态调整。</div>
              </el-form-item>
              <el-form-item label="产品厂商">
                <el-input v-model="product.vendor" maxlength="100" show-word-limit placeholder="可选"/>
              </el-form-item>
              <el-form-item label="产品型号">
                <el-input v-model="product.model" maxlength="100" show-word-limit placeholder="可选"/>
              </el-form-item>
            </div>

            <div class="mb-2 mt-3 text-[12px] font-medium text-gray-700">接入信息</div>
            <div class="grid grid-cols-1 gap-x-4 md:grid-cols-2">
              <el-form-item label="网络类型">
                <el-select v-model="product.netType" clearable class="!w-full" placeholder="请选择">
                  <el-option label="Wi-Fi" :value="1"/>
                  <el-option label="蜂窝网络" :value="2"/>
                  <el-option label="以太网" :value="3"/>
                  <el-option label="其他" :value="9"/>
                </el-select>
              </el-form-item>
            </div>

            <div class="mb-2 mt-3 text-[12px] font-medium text-gray-700">展示与说明</div>
            <div class="grid grid-cols-1 gap-x-4 md:grid-cols-2">
              <el-form-item label="产品图标">
                <div class="flex w-full items-center gap-2">
                  <div
                      class="flex h-7 w-7 shrink-0 items-center justify-center rounded border border-gray-200 bg-gray-50 text-gray-600">
                    <el-icon :size="17">
                      <component :is="getProductIcon(product.icon)"/>
                    </el-icon>
                  </div>
                  <el-popover v-model:visible="iconPickerVisible" placement="bottom-start" :width="440" trigger="click">
                    <template #reference>
                      <el-button size="small" :disabled="isReadonly">选择图标</el-button>
                    </template>
                    <el-input v-model="iconSearch" size="small" clearable placeholder="搜索图标，如 摄像、网关、车辆"/>
                    <div class="mt-2 max-h-[300px] overflow-y-auto pr-1">
                      <template v-for="group in iconGroups" :key="group.name">
                        <div v-if="group.items.length" class="mb-3">
                          <div class="mb-1 text-[11px] font-medium text-gray-500">{{ group.name }}</div>
                          <div class="grid grid-cols-6 gap-1">
                            <button v-for="option in group.items" :key="option.key" type="button"
                                    class="flex h-14 flex-col items-center justify-center gap-1 rounded border text-[11px] transition-colors hover:border-blue-400 hover:text-blue-600"
                                    :class="product.icon === option.key ? 'border-blue-500 bg-blue-50 text-blue-600' : 'border-gray-200 text-gray-600'"
                                    :title="option.label" @click="selectProductIcon(option.key)">
                              <el-icon :size="18">
                                <component :is="option.component"/>
                              </el-icon>
                              <span class="max-w-full truncate px-1">{{ option.label }}</span>
                            </button>
                          </div>
                        </div>
                      </template>
                      <el-empty v-if="iconGroups.every(group => group.items.length === 0)" :image-size="42"
                                description="未找到图标"/>
                    </div>
                    <div class="mt-2 flex justify-end border-t border-gray-100 pt-2">
                      <el-button text size="small" @click="clearProductIcon">恢复默认</el-button>
                    </div>
                  </el-popover>
                  <span class="text-[11px] text-gray-400">{{ product.icon || '默认：芯片' }}</span>
                </div>
              </el-form-item>
              <el-form-item label="产品图片">
                <div class="flex w-full items-center gap-2">
                  <input ref="productImageInputRef" type="file" accept="image/jpeg,image/png,image/webp" class="hidden"
                         @change="handleProductImageChange">
                  <el-button size="small" :disabled="isReadonly || productImageUploading"
                             :loading="productImageUploading"
                             @click="productImageInputRef?.click()">
                    <el-icon>
                      <Upload/>
                    </el-icon>
                    上传图片
                  </el-button>
                  <el-button v-if="product.iconUrl" text type="danger" size="small" :disabled="isReadonly"
                             @click="product.iconUrl = ''">移除图片
                  </el-button>
                  <span class="text-[11px] text-gray-400">JPG、PNG、WebP，最大 5 MB；上传后保存基础配置生效。</span>
                </div>
                <img v-if="product.iconUrl" :src="product.iconUrl" alt="产品封面预览"
                     class="mt-2 h-16 w-16 rounded border border-gray-200 object-cover">
              </el-form-item>
            </div>
            <el-form-item label="产品描述">
              <el-input v-model="product.description" type="textarea" :rows="2"
                        placeholder="说明产品用途、接入限制与适用场景"/>
            </el-form-item>

            <div class="mb-2 mt-3 flex items-center justify-between text-[12px] font-medium text-gray-700">
              <span>静态规格</span>
              <el-button text type="primary" size="small" :disabled="isReadonly" @click="addAttribute">
                <el-icon>
                  <Plus/>
                </el-icon>
                添加规格
              </el-button>
            </div>
            <el-alert class="mb-2" type="info" :closable="false"
                      title="静态规格仅用于展示；需要按此字段检索产品时，请使用标签。"/>
            <div v-for="(attribute, index) in attributes" :key="index" class="mb-2 grid grid-cols-[1fr_1fr_auto] gap-2">
              <el-input v-model="attribute.key" :disabled="isReadonly" placeholder="规格名称，如 warranty"/>
              <el-input v-model="attribute.value" :disabled="isReadonly" placeholder="规格值，如 24个月"/>
              <el-button text type="danger" size="small" :disabled="isReadonly" @click="attributes.splice(index, 1)">
                <el-icon>
                  <Delete/>
                </el-icon>
              </el-button>
            </div>
            <el-empty v-if="attributes.length === 0" description="暂无静态规格" :image-size="48"/>
          </el-form>
        </div>
      </el-tab-pane>

      <el-tab-pane label="物模型" name="thing-model" class="min-h-0 overflow-auto">
        <div class="py-2">
          <el-alert v-if="isNew" class="mb-2" type="info" :closable="false"
                    :title="`请先在“概览与接入”创建${productLabel}，再配置物模型。`"/>
          <div class="mb-2 flex items-center justify-between">
            <el-segmented v-model="modelKind" :options="modelKindOptions" size="small"/>
            <el-button type="primary" size="small" :disabled="isReadonly || isNew" @click="openModelDialog()">
              <el-icon>
                <Plus/>
              </el-icon>
              新增{{ modelKindLabel }}
            </el-button>
          </div>
          <el-table :data="currentModels" border stripe size="small" class="w-full">
            <el-table-column prop="identifier" label="标识符" min-width="150"/>
            <el-table-column :prop="modelNameKey" label="名称" min-width="130"/>
            <el-table-column v-if="modelKind === 'property'" prop="dataType" label="数据类型" width="110"/>
            <el-table-column v-if="modelKind === 'property'" label="读写" width="90">
              <template #default="{row}">{{ accessModeLabel(row.accessMode) }}</template>
            </el-table-column>
            <el-table-column v-if="modelKind === 'property'" prop="unit" label="单位" width="90"/>
            <el-table-column v-if="modelKind !== 'service'" label="数据保留" width="100">
              <template #default="{row}">
                {{ modelKind === 'event' ? eventRetentionLabel(row) : retentionLabel(row.retentionDays) }}
              </template>
            </el-table-column>
            <el-table-column v-if="modelKind !== 'property'" :prop="modelKind === 'event' ? 'eventType' : 'callType'"
                             label="类型" width="90">
              <template #default="{row}">
                {{ modelKind === 'event' ? eventTypeLabel(row.eventType) : callTypeLabel(row.callType) }}
              </template>
            </el-table-column>
            <el-table-column label="操作" fixed="right" width="96">
              <template #default="{row}">
                <el-button link type="primary" size="small" :disabled="isReadonly || isNew"
                           @click="openModelDialog(row)">编辑
                </el-button>
                <el-button link type="danger" size="small" :disabled="isReadonly || isNew" @click="removeModel(row)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="currentModels.length === 0" :description="`暂无${modelKindLabel}，请新增产品能力定义`"
                    :image-size="72"/>
        </div>
      </el-tab-pane>

      <el-tab-pane label="设备模板" name="schema" class="min-h-0 overflow-auto">
        <el-alert v-if="isNew" class="mb-2" type="info" :closable="false"
                  :title="`请先创建${productLabel}，再配置设备模板。`"/>
        <div class="mb-2 flex items-center justify-between"><span class="text-[12px] text-gray-500">定义每台设备创建时需要补充的私有配置，不填写具体设备位置。</span>
          <div class="flex gap-2">
            <el-button size="small" :disabled="isReadonly || isNew" @click="addSchemaGroup">
              <el-icon>
                <Plus/>
              </el-icon>
              新增分组
            </el-button>
            <el-button type="primary" size="small" :loading="schemaSaving" :disabled="isReadonly || isNew"
                       @click="saveSchema">保存设备模板
            </el-button>
          </div>
        </div>
        <el-alert class="mb-3" type="info" :closable="false"
                  title="通用经纬度、地址在建设备时填写；这里可定义安装点位、区域、车道号等产品私有安装字段。"
                  description="分组键、分组名称、字段键、字段名称、字段类型必填；枚举字段必须填写选项。“字段值必填”决定建设备时是否必须录入，默认值、搜索、列表展示与敏感标记均为可选。"/>
        <div class="mb-2 text-right text-[11px] text-gray-500"><em class="mr-0.5 not-italic text-red-500">*</em>为模板定义必填项
        </div>
        <div v-for="(group, groupIndex) in schema.groups" :key="groupIndex"
             class="mb-3 rounded border border-gray-200 p-3">
          <div class="mb-3 flex items-center gap-2">
            <div class="flex items-center gap-1">
              <span class="shrink-0 text-[12px] text-gray-600">分组名称<em class="ml-0.5 not-italic text-red-500">*</em></span>
              <el-input v-model="group.label" :disabled="isReadonly || isNew" maxlength="100" class="!w-[150px]"
                        placeholder="请输入"/>
            </div>
            <div class="flex items-center gap-1">
              <span class="shrink-0 text-[12px] text-gray-600">分组键<em
                  class="ml-0.5 not-italic text-red-500">*</em></span>
              <el-input v-model="group.key" :disabled="isReadonly || isNew" maxlength="50" class="!w-[150px]"
                        placeholder="如 install"/>
            </div>
            <el-button text type="primary" size="small" :disabled="isReadonly || isNew" @click="addSchemaField(group)">
              <el-icon>
                <Plus/>
              </el-icon>
              字段
            </el-button>
            <el-button text type="danger" size="small" :disabled="isReadonly || isNew"
                       @click="schema.groups.splice(groupIndex, 1)">
              <el-icon>
                <Delete/>
              </el-icon>
            </el-button>
          </div>
          <el-table :data="group.fields" border size="small">
            <el-table-column min-width="120">
              <template #header>字段键<em class="ml-0.5 not-italic text-red-500">*</em></template>
              <template #default="{row}">
                <el-input v-model="row.key" :disabled="isReadonly || isNew || !!row.persistedKey" maxlength="50"
                          placeholder="如 laneNo"/>
              </template>
            </el-table-column>
            <el-table-column min-width="120">
              <template #header>名称<em class="ml-0.5 not-italic text-red-500">*</em></template>
              <template #default="{row}">
                <el-input v-model="row.label" :disabled="isReadonly || isNew" maxlength="100"/>
              </template>
            </el-table-column>
            <el-table-column width="110">
              <template #header>类型<em class="ml-0.5 not-italic text-red-500">*</em></template>
              <template #default="{row}">
                <el-select v-model="row.type" :disabled="isReadonly || isNew">
                  <el-option v-for="type in fieldTypes" :key="type.value" :label="type.label" :value="type.value"/>
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="字段值必填" width="92">
              <template #default="{row}">
                <el-checkbox v-model="row.required" :disabled="isReadonly || isNew"/>
              </template>
            </el-table-column>
            <el-table-column label="可搜索" width="70">
              <template #default="{row}">
                <el-checkbox v-model="row.searchable" :disabled="isReadonly || isNew || row.sensitive"/>
              </template>
            </el-table-column>
            <el-table-column label="列表展示" width="82">
              <template #default="{row}">
                <el-checkbox v-model="row.listVisible" :disabled="isReadonly || isNew"/>
              </template>
            </el-table-column>
            <el-table-column label="敏感" width="60">
              <template #default="{row}">
                <el-checkbox v-model="row.sensitive" :disabled="isReadonly || isNew"
                             @change="handleSensitiveChange(row)"/>
              </template>
            </el-table-column>
            <el-table-column label="选项/默认值" min-width="180">
              <template #default="{row}">
                <template v-if="row.type === 'enum'">
                  <el-input v-model="row.optionsText" :disabled="isReadonly || isNew" placeholder="选项用逗号分隔"/>
                  <div v-if="!row.optionsText.trim()" class="mt-1 text-[11px] text-red-500"><em
                      class="mr-0.5 not-italic">*</em>枚举选项必填
                  </div>
                </template>
                <el-input v-else v-model="row.defaultText" :disabled="isReadonly || isNew" placeholder="默认值（可选）"/>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="56">
              <template #default="{row, $index}">
                <el-button text type="danger" size="small" :disabled="isReadonly || isNew"
                           @click="group.fields.splice($index, 1)">
                  <el-icon>
                    <Delete/>
                  </el-icon>
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <el-empty v-if="schema.groups.length === 0" description="暂无设备模板字段，可先添加“安装信息”或“接入参数”分组"
                  :image-size="72"/>
      </el-tab-pane>

      <el-tab-pane label="标签" name="tags" class="min-h-0 overflow-auto">
        <div class="w-full py-2">
          <el-alert class="mb-3" type="info" :closable="false"
                    :title="isNew ? `请先创建${productLabel}，再绑定标签。` : '标签用于跨产品归类与检索；颜色来自标签管理。'"/>
          <el-select v-model="selectedTagIds" multiple filterable clearable :disabled="isReadonly || isNew"
                     class="!w-full" placeholder="搜索并选择标签">
            <el-option v-for="tag in tags" :key="tag.tagId" :label="`${tag.tagKey}: ${tag.tagValue}`"
                       :value="tag.tagId"><span class="mr-2 inline-block h-2.5 w-2.5 rounded-full"
                                                :style="{background: tag.color || '#CBD5E1'}"/>{{ tag.tagKey }}:
              {{ tag.tagValue }}
            </el-option>
          </el-select>
          <div class="mt-4 flex justify-end">
            <el-button type="primary" size="small" :loading="tagSaving" :disabled="isReadonly || isNew"
                       @click="saveTags">保存标签
            </el-button>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="modelDialogVisible" :title="`${editingModel ? '编辑' : '新增'}${modelKindLabel}`" width="620px"
               class="compact-edit-dialog compact-edit-dialog-scrollable model-definition-dialog"
               :close-on-click-modal="false">
      <el-form ref="modelFormRef" :model="modelForm" :rules="modelRules" label-width="84px" size="small"
               class="compact-edit-form">
        <div class="grid grid-cols-1">
          <el-form-item label="标识符" prop="identifier">
            <el-input v-model="modelForm.identifier" :disabled="!!editingModel" maxlength="50"
                      placeholder="如 currentTemp"/>
          </el-form-item>
          <el-form-item label="名称" prop="name">
            <el-input v-model="modelForm.name" maxlength="100"/>
          </el-form-item>
        </div>
        <template v-if="modelKind === 'property'">
          <div class="grid grid-cols-1">
            <el-form-item label="数据类型" prop="dataType">
              <el-select v-model="modelForm.dataType" :disabled="!!editingModel" class="!w-full">
                <el-option v-for="type in dataTypes" :key="type" :label="type" :value="type"/>
              </el-select>
            </el-form-item>
            <el-form-item label="数据保留" prop="retentionDays">
              <el-select v-model="modelForm.retentionDays" :disabled="!!editingModel" class="!w-full">
                <el-option v-for="days in retentionDaysOptions" :key="days" :label="retentionLabel(days)"
                           :value="days"/>
              </el-select>
              <div class="mt-1 text-[11px] leading-none text-gray-400">属性创建后不可修改，决定历史数据物理表。</div>
            </el-form-item>
            <el-form-item label="读写模式">
              <el-select v-model="modelForm.accessMode" class="!w-full">
                <el-option label="只读" :value="1"/>
                <el-option label="读写" :value="2"/>
                <el-option label="只写" :value="3"/>
              </el-select>
            </el-form-item>
            <el-form-item label="单位">
              <el-input v-model="modelForm.unit" maxlength="20" placeholder="如 ℃"/>
            </el-form-item>
          </div>
        </template>
        <template v-else>
          <el-form-item :label="modelKind === 'event' ? '事件级别' : '调用方式'" prop="kindType">
            <el-select v-model="modelForm.kindType" class="!w-full">
              <el-option v-if="modelKind === 'event'" label="信息" :value="1"/>
              <el-option v-if="modelKind === 'event'" label="告警" :value="2"/>
              <el-option v-if="modelKind === 'event'" label="故障" :value="3"/>
              <el-option v-if="modelKind === 'service'" label="同步" :value="1"/>
              <el-option v-if="modelKind === 'service'" label="异步" :value="2"/>
            </el-select>
          </el-form-item>
          <el-form-item v-if="modelKind === 'event'" label="数据保留" prop="ttlEnabled">
            <div class="w-full">
              <el-radio-group v-model="modelForm.ttlEnabled" @change="handleTtlModeChange">
                <el-radio :value="false">使用默认值</el-radio>
                <el-radio :value="true">自定义保留时长</el-radio>
              </el-radio-group>
              <div v-if="modelForm.ttlEnabled" class="mt-2 grid max-w-[320px] grid-cols-[minmax(0,1fr)_120px] gap-2">
                <div>
                  <div class="mb-1 text-[11px] text-gray-500">数值<em class="ml-0.5 not-italic text-red-500">*</em>
                  </div>
                  <el-form-item prop="ttlValue" class="!mb-0">
                    <el-input-number v-model="modelForm.ttlValue" :min="1" :precision="0" :step="1"
                                     controls-position="right" class="!w-full" @keydown="preventInvalidIntegerKey"/>
                  </el-form-item>
                </div>
                <div>
                  <div class="mb-1 text-[11px] text-gray-500">单位<em class="ml-0.5 not-italic text-red-500">*</em>
                  </div>
                  <el-form-item prop="ttlUnit" class="!mb-0">
                    <el-select v-model="modelForm.ttlUnit" class="!w-full">
                      <el-option label="小时 (h)" value="h"/>
                      <el-option label="天 (d)" value="d"/>
                    </el-select>
                  </el-form-item>
                </div>
              </div>
              <div class="mt-2 rounded bg-gray-50 px-2 py-1.5 font-mono text-[11px] leading-5 text-gray-600">
                {{ eventTtlSqlPreview }}
              </div>
              <div class="mt-1 text-[11px] leading-4 text-gray-400">默认值继承 GreptimeDB 数据库 TTL；编辑时任何 TTL
                调整均需二次确认。
              </div>
            </div>
          </el-form-item>
          <div class="mb-2 flex items-center justify-between"><span
              class="text-[12px] text-gray-600">输入参数（每行均必填）</span>
            <el-button text type="primary" size="small" @click="addPrimaryParam">
              <el-icon>
                <Plus/>
              </el-icon>
              参数
            </el-button>
          </div>
          <div class="mb-1 grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_120px_28px] gap-2 text-[11px] text-gray-500">
            <span>标识符<em class="ml-0.5 not-italic text-red-500">*</em></span>
            <span>名称<em class="ml-0.5 not-italic text-red-500">*</em></span>
            <span>类型<em class="ml-0.5 not-italic text-red-500">*</em></span>
            <span></span>
          </div>
          <el-form-item v-for="(param, index) in primaryParams" :key="index" :label="`参数 ${index + 1}`"
                        :error="primaryParamErrors[index]" class="parameter-row-form-item">
            <div class="grid w-full grid-cols-[minmax(0,1fr)_minmax(0,1fr)_120px_28px] items-start gap-2">
              <el-input v-model="param.identifier" placeholder="标识符"
                        :disabled="isPersistedEventParam(param)"
                        @input="clearParameterError(primaryParameterErrorGroup, index)"/>
              <el-input v-model="param.name" placeholder="参数名称"
                        @input="clearParameterError(primaryParameterErrorGroup, index)"/>
              <el-select v-model="param.dataType" class="!w-full"
                         :disabled="isPersistedEventParam(param)"
                         @change="clearParameterError(primaryParameterErrorGroup, index)">
                <el-option v-for="type in dataTypes" :key="type" :label="type" :value="type"/>
              </el-select>
              <el-button text type="danger" class="!mt-1 !px-1" @click="removePrimaryParam(index)">
                <el-icon>
                  <Delete/>
                </el-icon>
              </el-button>
            </div>
          </el-form-item>
          <template v-if="modelKind === 'service'">
            <div class="mb-2 mt-4 flex items-center justify-between"><span
                class="text-[12px] text-gray-600">输出参数</span>
              <el-button text type="primary" size="small" @click="modelForm.outputParams.push(newParam())">
                <el-icon>
                  <Plus/>
                </el-icon>
                参数
              </el-button>
            </div>
            <el-form-item v-for="(param, index) in modelForm.outputParams" :key="index" :label="`参数 ${index + 1}`"
                          :error="parameterErrors.output[index]" class="parameter-row-form-item">
              <div class="grid w-full grid-cols-[minmax(0,1fr)_minmax(0,1fr)_120px_28px] items-start gap-2">
                <el-input v-model="param.identifier" placeholder="标识符"
                          @input="clearParameterError('output', index)"/>
                <el-input v-model="param.name" placeholder="参数名称" @input="clearParameterError('output', index)"/>
                <el-select v-model="param.dataType" class="!w-full" @change="clearParameterError('output', index)">
                  <el-option v-for="type in dataTypes" :key="type" :label="type" :value="type"/>
                </el-select>
                <el-button text type="danger" class="!mt-1 !px-1" @click="modelForm.outputParams.splice(index, 1)">
                  <el-icon>
                    <Delete/>
                  </el-icon>
                </el-button>
              </div>
            </el-form-item>
          </template>
        </template>
      </el-form>
      <template #footer>
        <el-button size="small" @click="modelDialogVisible = false">取消</el-button>
        <el-button type="primary" size="small" :loading="modelSaving" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import type {FormInstance, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {
  createProduct,
  createTmEvent,
  createTmProperty,
  createTmService,
  deleteTmEvent,
  deleteTmProperty,
  deleteTmService,
  getProduct,
  listTags,
  listTmEvents,
  listTmProperties,
  listTmServices,
  replaceProductTags,
  type TagVO,
  type TmEventVO,
  type TmPropertyVO,
  type TmServiceVO,
  updateProduct,
  updateTmEvent,
  updateTmProperty,
  updateTmService
} from '@/api/device'
import {uploadFile} from '@/api/system'
import {getErrorMessage, isCancelError} from '@/utils/error'
import {getProductIcon, productIconOptions} from '@/components/device/product-icons'

type ModelKind = 'property' | 'event' | 'service'
type SchemaField = {
  key: string;
  label: string;
  type: string;
  required: boolean;
  searchable: boolean;
  sensitive: boolean;
  listVisible: boolean;
  defaultText: string;
  optionsText: string;
  mask?: string
  /** 已保存字段的不可变键；新增字段保存前为空。 */
  persistedKey?: string
}
type SchemaGroup = { key: string; label: string; fields: SchemaField[] }
type Param = { identifier: string; name: string; dataType: string }

const route = useRoute()
const router = useRouter()
const props = withDefaults(defineProps<{ mode?: 'standard' | 'product' }>(), {mode: 'standard'})
const formRef = ref<FormInstance>()
const productImageInputRef = ref<HTMLInputElement>()
const modelFormRef = ref<FormInstance>()
const activeTab = ref('overview')
const saving = ref(false)
const schemaSaving = ref(false)
const tagSaving = ref(false)
const modelSaving = ref(false)
const isNew = computed(() => route.name === 'IotStandardProductCreate' || route.name === 'IotProductCreate')
const productLabel = computed(() => '产品')
const productType = computed(() => props.mode === 'standard' ? 2 : 1)
const editing = ref(isNew.value || route.query.edit === '1')
const productId = computed(() => Number(route.params.productId))
const product = reactive({
  productName: '',
  nodeType: 1,
  netType: undefined as number | undefined,
  vendor: '',
  model: '',
  icon: '',
  iconUrl: '',
  description: '',
  version: 0,
  productKey: ''
})
const attributes = ref<{ key: string; value: string }[]>([])
const schema = reactive<{ groups: SchemaGroup[] }>({groups: []})
const tags = ref<TagVO[]>([])
const selectedTagIds = ref<number[]>([])
const iconPickerVisible = ref(false)
const iconSearch = ref('')
const productImageUploading = ref(false)
const properties = ref<TmPropertyVO[]>([])
const events = ref<TmEventVO[]>([])
const services = ref<TmServiceVO[]>([])
const modelKind = ref<ModelKind>('property')
const modelKindOptions = [{label: '属性', value: 'property'}, {label: '事件', value: 'event'}, {
  label: '服务',
  value: 'service'
}]
const fieldTypes = [{label: '文本', value: 'string'}, {label: '整数', value: 'int'}, {
  label: '小数',
  value: 'float'
}, {label: '开关', value: 'bool'}, {label: '枚举', value: 'enum'}, {
  label: '密码',
  value: 'password'
}, {label: '多行文本', value: 'text'}]
const dataTypes = ['int', 'float', 'double', 'bool', 'enum', 'string', 'text', 'image']
const retentionDaysOptions = [90, 180, 360] as const
type TtlUnit = 'h' | 'd'
const productRules: FormRules = {
  productName: [{required: true, message: '请输入产品名称', trigger: 'blur'}]
}
const isReadonly = computed(() => !isNew.value && !editing.value)
const completion = computed(() => Math.round(((product.productName ? 1 : 0) + (properties.value.length + events.value.length + services.value.length > 0 ? 1 : 0) + (schema.groups.length > 0 ? 1 : 0) + 1) / 4 * 100))
const modelKindLabel = computed(() => ({property: '属性', event: '事件', service: '服务'})[modelKind.value])
const modelNameKey = computed(() => modelKind.value === 'property' ? 'propertyName' : modelKind.value === 'event' ? 'eventName' : 'serviceName')
const currentModels = computed(() => modelKind.value === 'property' ? properties.value : modelKind.value === 'event' ? events.value : services.value)
const iconGroups = computed(() => {
  const keyword = iconSearch.value.trim().toLowerCase();
  const names = ['设备与计算', '网络与连接', '环境与传感', '安防与视频', '工业与设施', '通用', '其他图标'] as const;
  return names.map(name => ({
    name,
    items: productIconOptions.filter(option => option.group === name && (!keyword || option.label.includes(keyword) || option.key.toLowerCase().includes(keyword)))
  }))
})
const primaryParams = computed(() => modelForm.inputParams)
const parameterErrors = reactive({input: [] as string[], output: [] as string[]})
const primaryParameterErrorGroup = computed<'input' | 'output'>(() => 'input')
const primaryParamErrors = computed(() => parameterErrors[primaryParameterErrorGroup.value])
const modelDialogVisible = ref(false)
const editingModel = ref<TmPropertyVO | TmEventVO | TmServiceVO>()
const persistedEventParamIds = ref<Set<string>>(new Set())

interface ModelFormState {
  identifier: string
  name: string
  dataType: string
  accessMode: number
  unit: string
  retentionDays: number
  ttlEnabled: boolean
  ttlValue: number | null
  ttlUnit: TtlUnit | null
  kindType: number
  inputParams: Param[]
  outputParams: Param[]
}

const modelForm = reactive<ModelFormState>({
  identifier: '',
  name: '',
  dataType: 'float',
  accessMode: 1,
  unit: '',
  retentionDays: 180,
  ttlEnabled: true,
  ttlValue: 7,
  ttlUnit: 'd',
  kindType: 1,
  inputParams: [] as Param[],
  outputParams: [] as Param[]
})
const modelRules: FormRules = {
  identifier: [{required: true, message: '请输入标识符', trigger: 'blur'}],
  name: [{required: true, message: '请输入名称', trigger: 'blur'}],
  dataType: [{required: true, message: '请选择数据类型', trigger: 'change'}],
  retentionDays: [{required: true, message: '请选择数据保留时间', trigger: 'change'}],
  ttlEnabled: [{required: true, message: '请选择事件数据保留策略', trigger: 'change'}],
  ttlValue: [{
    validator: (_rule, value, callback) => {
      if (!modelForm.ttlEnabled || (Number.isInteger(value) && value > 0)) callback()
      else callback(new Error('请输入正整数保留时长'))
    }, trigger: ['blur', 'change']
  }],
  ttlUnit: [{
    validator: (_rule, value, callback) => {
      if (!modelForm.ttlEnabled || value === 'h' || value === 'd') callback()
      else callback(new Error('请选择小时或天'))
    }, trigger: 'change'
  }],
  kindType: [{required: true, message: '请选择类型', trigger: 'change'}]
}

function resetModelForm() {
  Object.assign(modelForm, {
    identifier: '',
    name: '',
    dataType: 'float',
    accessMode: 1,
    unit: '',
    retentionDays: 180,
    ttlEnabled: true,
    ttlValue: 7,
    ttlUnit: 'd',
    kindType: 1,
    inputParams: [],
    outputParams: []
  })
  parameterErrors.input.splice(0)
  parameterErrors.output.splice(0)
  persistedEventParamIds.value = new Set()
}

function startEditing() {
  editing.value = true
}

function isPersistedEventParam(param: Param): boolean {
  return modelKind.value === 'event' && persistedEventParamIds.value.has(param.identifier.trim().toLowerCase())
}

function newParam(): Param {
  return {identifier: '', name: '', dataType: 'string'}
}

function addPrimaryParam() {
  primaryParams.value.push(newParam())
}

async function removePrimaryParam(index: number) {
  const param = primaryParams.value[index]
  if (param && isPersistedEventParam(param)) {
    try {
      await ElMessageBox.confirm('该列将从时序表 DROP，历史值不可恢复', '删除参数', {type: 'warning'})
    } catch {
      return
    }
  }
  primaryParams.value.splice(index, 1)
}

function addAttribute() {
  attributes.value.push({key: '', value: ''})
}

function selectProductIcon(icon: string) {
  product.icon = icon;
  iconPickerVisible.value = false
}

function clearProductIcon() {
  product.icon = '';
  iconPickerVisible.value = false
}

async function handleProductImageChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = '';
  if (!file) return;
  const supportedTypes = ['image/jpeg', 'image/png', 'image/webp'];
  if (!supportedTypes.includes(file.type)) {
    ElMessage.error('仅支持 JPG、PNG、WebP 格式的产品图片');
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('产品图片不能超过 5 MB');
    return
  }
  productImageUploading.value = true;
  try {
    const response = await uploadFile(file, 'product-images');
    product.iconUrl = response.data?.fileUrl || '';
    if (!product.iconUrl) {
      ElMessage.error('上传成功但未返回图片地址');
      return
    }
    ElMessage.success('图片已上传，请保存基础配置')
  } catch (error: unknown) {
    console.error('上传产品图片失败', error);
    ElMessage.error(getErrorMessage(error, '上传产品图片失败'))
  } finally {
    productImageUploading.value = false
  }
}

function addSchemaGroup() {
  schema.groups.push({key: '', label: '', fields: []})
}

function addSchemaField(group: SchemaGroup) {
  group.fields.push({
    key: '',
    label: '',
    type: 'string',
    required: false,
    searchable: false,
    sensitive: false,
    listVisible: false,
    defaultText: '',
    optionsText: ''
  })
}

function handleSensitiveChange(field: SchemaField) {
  if (field.sensitive) {
    field.searchable = false;
    field.mask = field.type === 'password' ? 'all' : 'all'
  } else field.mask = undefined
}

function goBack() {
  router.push({name: props.mode === 'standard' ? 'IotStandardProduct' : 'IotProduct'})
}

function accessModeLabel(value?: number) {
  return ({1: '只读', 2: '读写', 3: '只写'} as Record<number, string>)[value || 1]
}

function eventTypeLabel(value?: number) {
  return ({1: '信息', 2: '告警', 3: '故障'} as Record<number, string>)[value || 1]
}

function retentionLabel(value?: number) {
  return value ? `${value} 天` : '-'
}

function eventRetentionLabel(event: TmEventVO) {
  return event.ttlEnabled ? `${event.ttlValue}${event.ttlUnit}` : '默认'
}

function handleTtlModeChange(enabled: string | number | boolean | undefined) {
  if (enabled === true) {
    modelForm.ttlValue = 7
    modelForm.ttlUnit = 'd'
  } else {
    modelForm.ttlValue = null
    modelForm.ttlUnit = null
  }
  modelFormRef.value?.clearValidate(['ttlValue', 'ttlUnit'])
}

function preventInvalidIntegerKey(event: KeyboardEvent) {
  if (['e', 'E', '+', '-', '.'].includes(event.key)) event.preventDefault()
}

function generateEventTtlSql(tableName: string, enabled: boolean, value: number | null, unit: TtlUnit | null) {
  const escapedTableName = `\`${tableName.replace(/`/g, '``')}\``
  if (!enabled) return `ALTER TABLE ${escapedTableName} UNSET 'ttl';`
  if (!Number.isInteger(value) || (value ?? 0) <= 0 || !unit) return '-- 请输入有效的正整数保留时长'
  return `ALTER TABLE ${escapedTableName} SET 'ttl'='${value}${unit}';`
}

const eventTtlSqlPreview = computed(() => {
  const identifier = modelForm.identifier.trim().toLowerCase() || 'event_identifier'
  const tableName = `evt_${product.productKey || 'product'}_${identifier}`
  return generateEventTtlSql(tableName, modelForm.ttlEnabled, modelForm.ttlValue, modelForm.ttlUnit)
})

function callTypeLabel(value?: number) {
  return ({1: '同步', 2: '异步'} as Record<number, string>)[value || 1]
}

function toAttributes() {
  return Object.fromEntries(attributes.value.filter(item => item.key.trim()).map(item => [item.key.trim(), item.value]))
}

function toSchema() {
  return {
    groups: schema.groups.map(group => ({
      key: group.key.trim(),
      label: group.label.trim(),
      fields: group.fields.map(field => ({
        key: field.key.trim(),
        label: field.label.trim(),
        type: field.type,
        required: field.required,
        searchable: field.sensitive ? false : field.searchable,
        sensitive: field.sensitive,
        listVisible: field.listVisible, ...(field.defaultText ? {default: field.defaultText} : {}), ...(field.type === 'enum' ? {options: field.optionsText.split(',').map(value => value.trim()).filter(Boolean)} : {}), ...(field.sensitive ? {mask: field.mask || 'all'} : {})
      }))
    }))
  }
}

function validateSchema(): boolean {
  const groupKeys = new Set<string>();
  const fieldKeys = new Set<string>();
  for (let groupIndex = 0; groupIndex < schema.groups.length; groupIndex += 1) {
    const group = schema.groups[groupIndex];
    const groupName = `第 ${groupIndex + 1} 个分组`;
    const groupKey = group.key.trim();
    if (!groupKey || !group.label.trim()) {
      ElMessage.error(`${groupName}的分组键和分组名称为必填项`);
      activeTab.value = 'schema';
      return false
    }
    if (groupKeys.has(groupKey)) {
      ElMessage.error(`分组键“${groupKey}”重复`);
      activeTab.value = 'schema';
      return false
    }
    groupKeys.add(groupKey);
    if (group.fields.length === 0) {
      ElMessage.error(`${groupName}至少需要一个字段`);
      activeTab.value = 'schema';
      return false
    }
    for (let fieldIndex = 0; fieldIndex < group.fields.length; fieldIndex += 1) {
      const field = group.fields[fieldIndex];
      const fieldName = `${groupName}的第 ${fieldIndex + 1} 个字段`;
      const fieldKey = field.key.trim();
      if (!fieldKey || !field.label.trim() || !field.type) {
        ElMessage.error(`${fieldName}的字段键、名称和类型为必填项`);
        activeTab.value = 'schema';
        return false
      }
      if (fieldKeys.has(fieldKey)) {
        ElMessage.error(`字段键“${fieldKey}”重复；同一设备模板内字段键必须唯一`);
        activeTab.value = 'schema';
        return false
      }
      fieldKeys.add(fieldKey);
      if (field.type === 'enum' && !field.optionsText.split(',').some(value => value.trim())) {
        ElMessage.error(`${fieldName}为枚举类型，必须至少填写一个选项`);
        activeTab.value = 'schema';
        return false
      }
    }
  }
  return true
}

function applySchema(source?: Record<string, unknown>) {
  schema.groups.splice(0);
  const groups = Array.isArray(source?.groups) ? source.groups : [];
  groups.forEach(rawGroup => {
    const group = rawGroup as { key?: string; label?: string; fields?: unknown[] };
    schema.groups.push({
      key: group.key || '', label: group.label || '', fields: (group.fields || []).map(rawField => {
        const field = rawField as Record<string, unknown>;
        return {
          key: String(field.key || ''),
          label: String(field.label || ''),
          type: String(field.type || 'string'),
          required: Boolean(field.required),
          searchable: Boolean(field.searchable),
          sensitive: Boolean(field.sensitive),
          listVisible: Boolean(field.listVisible),
          defaultText: field.default === undefined ? '' : String(field.default),
          optionsText: Array.isArray(field.options) ? field.options.join(', ') : '',
          mask: typeof field.mask === 'string' ? field.mask : undefined,
          persistedKey: String(field.key || '')
        }
      })
    })
  })
}

async function loadWorkbench() {
  editing.value = isNew.value || route.query.edit === '1'
  if (isNew.value) return;
  try {
    const [productRes, propertyRes, eventRes, serviceRes, tagRes] = await Promise.all([getProduct(productId.value), listTmProperties(productId.value), listTmEvents(productId.value), listTmServices(productId.value), listTags({
      pageNum: 1,
      pageSize: 200,
      query: {}
    })]);
    const detail = productRes.data;
    if (!detail || detail.productType !== productType.value) {
      ElMessage.error(`${productLabel.value}不存在`);
      goBack();
      return
    }
    Object.assign(product, detail);
    attributes.value = Object.entries(detail.attributes || {}).map(([key, value]) => ({key, value: String(value)}));
    applySchema(detail.deviceFormSchema);
    properties.value = propertyRes.data || [];
    events.value = eventRes.data || [];
    services.value = serviceRes.data || [];
    tags.value = tagRes.data?.list || [];
    selectedTagIds.value = (detail.tags || []).map(tag => tag.tagId)
  } catch (error: unknown) {
    console.error(`加载${productLabel.value}建模工作台失败`, error);
    ElMessage.error(getErrorMessage(error, `加载${productLabel.value}失败`))
  }
}

async function saveProduct(options: { successMessage?: string } = {}): Promise<boolean> {
  if (isReadonly.value) return false;
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid || !validateSchema()) return false;
  saving.value = true;
  try {
    const payload = {
      productName: product.productName,
      nodeType: product.nodeType,
      netType: product.netType,
      vendor: product.vendor || undefined,
      model: product.model || undefined,
      icon: product.icon || undefined,
      iconUrl: product.iconUrl || undefined,
      description: product.description || undefined,
      attributes: toAttributes(),
      deviceFormSchema: toSchema(),
      productType: productType.value,
      version: product.version
    };
    if (isNew.value) {
      const result = await createProduct(payload);
      ElMessage.success(`${productLabel.value}已创建，请继续配置物模型`);
      await router.replace({
        name: props.mode === 'standard' ? 'IotStandardProductWorkbench' : 'IotProductWorkbench',
        params: {productId: result.data}
      })
    } else {
      await updateProduct(productId.value, payload);
      product.version += 1;
      applySchema(toSchema())
      ElMessage.success(options.successMessage || '基础配置已保存')
    }
    return true
  } catch (error: unknown) {
    console.error(`保存${productLabel.value}失败`, error);
    ElMessage.error(getErrorMessage(error, `保存${productLabel.value}失败`))
    return false
  } finally {
    saving.value = false
  }
}

async function saveSchema() {
  schemaSaving.value = true;
  try {
    await saveProduct({successMessage: '设备模板已保存'})
  } finally {
    schemaSaving.value = false
  }
}

async function saveTags() {
  tagSaving.value = true;
  try {
    await replaceProductTags(productId.value, selectedTagIds.value);
    ElMessage.success('标签已保存')
  } catch (error: unknown) {
    console.error(`保存${productLabel.value}标签失败`, error);
    ElMessage.error(getErrorMessage(error, '保存标签失败'))
  } finally {
    tagSaving.value = false
  }
}

function openModelDialog(item?: TmPropertyVO | TmEventVO | TmServiceVO) {
  resetModelForm();
  editingModel.value = item;
  if (item) {
    modelForm.identifier = item.identifier;
    if (modelKind.value === 'property') {
      const property = item as TmPropertyVO;
      modelForm.name = property.propertyName;
      modelForm.dataType = property.dataType;
      modelForm.accessMode = property.accessMode || 1;
      modelForm.unit = property.unit || '';
      modelForm.retentionDays = property.retentionDays;
    } else if (modelKind.value === 'event') {
      const event = item as TmEventVO;
      modelForm.name = event.eventName;
      modelForm.kindType = event.eventType || 1;
      modelForm.ttlEnabled = event.ttlEnabled;
      modelForm.ttlValue = event.ttlValue ?? null;
      modelForm.ttlUnit = event.ttlUnit ?? null;
      modelForm.inputParams = (event.inputParams || []).map(param => ({
        identifier: String(param.identifier || ''),
        name: String(param.name || ''),
        dataType: String(param.dataType || 'string'),
      }))
      const paramIds = new Set<string>()
      for (const param of modelForm.inputParams) {
        const id = param.identifier.trim().toLowerCase()
        if (id) {
          paramIds.add(id)
        }
      }
      persistedEventParamIds.value = paramIds
    } else {
      const service = item as TmServiceVO;
      modelForm.name = service.serviceName;
      modelForm.kindType = service.callType || 1;
      modelForm.inputParams = (service.inputParams || []).map(param => ({
        identifier: String(param.identifier || ''),
        name: String(param.name || ''),
        dataType: String(param.dataType || 'string'),
      }));
      modelForm.outputParams = (service.outputParams || []).map(param => ({
        identifier: String(param.identifier || ''),
        name: String(param.name || ''),
        dataType: String(param.dataType || 'string'),
      }))
    }
  }
  modelDialogVisible.value = true
}

async function saveModel() {
  const valid = await modelFormRef.value?.validate().catch(() => false);
  if (!valid) return;
  if (!validateParameters()) return;
  if (modelKind.value === 'event' && !(await confirmDangerousTtlChange())) return;
  modelSaving.value = true;
  try {
    if (modelKind.value === 'property') {
      const payload = {
        identifier: modelForm.identifier.trim(),
        propertyName: modelForm.name.trim(),
        dataType: modelForm.dataType,
        accessMode: modelForm.accessMode,
        unit: modelForm.unit || undefined,
        retentionDays: modelForm.retentionDays,
        version: (editingModel.value as TmPropertyVO | undefined)?.version
      };
      if (editingModel.value) await updateTmProperty(productId.value, (editingModel.value as TmPropertyVO).propertyId, payload); else await createTmProperty(productId.value, payload)
    } else if (modelKind.value === 'event') {
      const payload = {
        identifier: modelForm.identifier.trim(),
        eventName: modelForm.name.trim(),
        eventType: modelForm.kindType,
        ttlEnabled: modelForm.ttlEnabled,
        ttlValue: modelForm.ttlEnabled ? modelForm.ttlValue ?? undefined : undefined,
        ttlUnit: modelForm.ttlEnabled ? modelForm.ttlUnit ?? undefined : undefined,
        inputParams: modelForm.inputParams,
        version: (editingModel.value as TmEventVO | undefined)?.version
      };
      if (editingModel.value) await updateTmEvent(productId.value, (editingModel.value as TmEventVO).eventId, payload); else await createTmEvent(productId.value, payload)
    } else {
      const payload = {
        identifier: modelForm.identifier.trim(),
        serviceName: modelForm.name.trim(),
        callType: modelForm.kindType,
        inputParams: modelForm.inputParams,
        outputParams: modelForm.outputParams,
        version: (editingModel.value as TmServiceVO | undefined)?.version
      };
      if (editingModel.value) await updateTmService(productId.value, (editingModel.value as TmServiceVO).serviceId, payload); else await createTmService(productId.value, payload)
    }
    modelDialogVisible.value = false;
    await loadModels();
    ElMessage.success('物模型已保存')
  } catch (error: unknown) {
    console.error('保存物模型失败', error);
    ElMessage.error(getErrorMessage(error, '保存物模型失败'))
  } finally {
    modelSaving.value = false
  }
}

async function confirmDangerousTtlChange() {
  const previous = editingModel.value as TmEventVO | undefined
  if (!previous) return true
  const previousValue = previous.ttlEnabled ? previous.ttlValue : undefined
  const previousUnit = previous.ttlEnabled ? previous.ttlUnit : undefined
  const ttlChanged = previous.ttlEnabled !== modelForm.ttlEnabled
      || previousValue !== (modelForm.ttlEnabled ? modelForm.ttlValue ?? undefined : undefined)
      || previousUnit !== (modelForm.ttlEnabled ? modelForm.ttlUnit ?? undefined : undefined)
  if (!ttlChanged) return true
  const tableName = `evt_${product.productKey}_${modelForm.identifier.trim().toLowerCase()}`
  const targetPolicy = modelForm.ttlEnabled
      ? `${modelForm.ttlValue}${modelForm.ttlUnit}`
      : '数据库默认 TTL'
  try {
    await ElMessageBox.confirm(
        `您即将将表 ${tableName} 的保留策略修改为 ${targetPolicy}。\n\n数据风险提示：如果新策略缩短了实际保留时长，过期历史数据将在下一次后台压缩（Compaction）时被物理删除且无法恢复。`,
        '⚠️ 确认修改数据保留策略 (TTL)',
        {
          type: 'warning',
          confirmButtonText: '确认修改数据保留策略',
          cancelButtonText: '取消',
          confirmButtonClass: 'el-button--danger'
        }
    )
    return true
  } catch (error: unknown) {
    if (isCancelError(error)) return false
    throw error
  }
}

function validateParameters(): boolean {
  parameterErrors.input.splice(0)
  parameterErrors.output.splice(0)
  const groups: { key: 'input' | 'output'; params: Param[] }[] = modelKind.value === 'event'
      ? [{key: 'input', params: modelForm.inputParams}]
      : modelKind.value === 'service'
          ? [{key: 'input', params: modelForm.inputParams}, {key: 'output', params: modelForm.outputParams}]
          : [];
  let valid = true;
  for (const group of groups) {
    group.params.forEach((param, index) => {
      if (!param.identifier.trim() || !param.name.trim() || !param.dataType) {
        parameterErrors[group.key][index] = '标识符、名称和数据类型均为必填项';
        valid = false
      }
    })
  }
  return valid
}

function clearParameterError(group: 'input' | 'output', index: number) {
  parameterErrors[group][index] = ''
}

async function loadModels() {
  const [propertyRes, eventRes, serviceRes] = await Promise.all([listTmProperties(productId.value), listTmEvents(productId.value), listTmServices(productId.value)]);
  properties.value = propertyRes.data || [];
  events.value = eventRes.data || [];
  services.value = serviceRes.data || []
}

async function removeModel(item: TmPropertyVO | TmEventVO | TmServiceVO) {
  try {
    await ElMessageBox.confirm(`确定删除${modelKindLabel.value}「${item.identifier}」？`, '提示', {type: 'warning'});
    if (modelKind.value === 'property') await deleteTmProperty(productId.value, (item as TmPropertyVO).propertyId); else if (modelKind.value === 'event') await deleteTmEvent(productId.value, (item as TmEventVO).eventId); else await deleteTmService(productId.value, (item as TmServiceVO).serviceId);
    await loadModels();
    ElMessage.success('已删除')
  } catch (error: unknown) {
    if (isCancelError(error)) return;
    console.error('删除物模型失败', error);
    ElMessage.error(getErrorMessage(error, '删除物模型失败'))
  }
}

watch(() => [route.params.productId, route.query.edit], loadWorkbench, {immediate: true})
</script>

<style scoped>
.workbench-tabs :deep(.el-tabs__header) {
  flex: none;
  margin-bottom: 4px;
}

.workbench-tabs :deep(.el-tabs__item) {
  height: 28px;
  padding: 0 10px;
  font-size: 11px;
  line-height: 28px;
}

.workbench-tabs :deep(.el-tabs__active-bar) {
  height: 2px;
}

.workbench-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

.workbench-tabs :deep(.el-tab-pane) {
  height: 100%;
}
</style>
