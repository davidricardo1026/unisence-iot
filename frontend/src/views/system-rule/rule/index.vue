<template>
  <div class="flex h-full min-h-0 flex-col bg-white p-3">
    <el-form
        :inline="true"
        :model="query"
        size="small"
        class="compact-query-form mb-2 shrink-0"
    >
      <el-form-item
          label="关键词"
          class="!mb-0"
      >
        <el-input
            v-model="query.keyword"
            clearable
            class="!w-[160px]"
            placeholder="名称 / 编码"
            @keyup.enter="search"
        />
      </el-form-item>
      <el-form-item
          label="消息类型"
          class="!mb-0"
      >
        <el-select
            v-model="query.messageType"
            clearable
            placeholder="全部"
            class="!w-[150px]"
        >
          <el-option
              v-for="item in modelTypes"
              :key="item.value"
              :label="item.label"
              :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item
          label="状态"
          class="!mb-0"
      >
        <el-select
            v-model="query.status"
            clearable
            placeholder="全部"
            class="!w-[100px]"
        >
          <el-option
              label="停用"
              :value="0"
          />
          <el-option
              label="启用"
              :value="1"
          />
        </el-select>
      </el-form-item>
      <div class="flex gap-1 !ml-auto">
        <el-button
            v-hasPermi="['iot:rule:list']"
            type="primary"
            size="small"
            @click="search"
        >
          搜索
        </el-button>
        <el-button
            size="small"
            @click="resetQuery"
        >
          重置
        </el-button>
      </div>
    </el-form>

    <div class="compact-action-toolbar">
      <el-radio-group
          v-model="kind"
          size="small"
          @change="switchKind"
      >
        <el-radio-button value="INSTANT">
          即时规则
        </el-radio-button>
        <el-radio-button value="WINDOW">
          窗口规则
        </el-radio-button>
      </el-radio-group>
      <el-button
          v-hasPermi="['iot:rule:add']"
          type="primary"
          size="small"
          @click="openCreate"
      >
        <el-icon>
          <Plus/>
        </el-icon>
        {{ kind === 'INSTANT' ? '新建即时规则' : '新建窗口规则' }}
      </el-button>
      <div class="ml-auto flex items-center gap-2">
        <span class="text-[11px] text-gray-400">{{ kindHint }}</span>
        <span
            v-if="kind === 'WINDOW' && !capabilities.windowRule.runtimeInstalled"
            class="text-[11px] text-amber-600"
        >当前版本可配置，但未安装窗口执行能力</span>
        <span class="text-[11px] text-gray-400">规则保存后默认停用</span>
        <span class="text-[11px] text-gray-400">共 {{ total }} 条</span>
      </div>
    </div>

    <div class="compact-table-region">
      <el-table
          v-loading="loading"
          :data="rows"
          border
          stripe
          size="small"
          height="100%"
      >
        <el-table-column
            prop="ruleName"
            label="规则名称"
            min-width="160"
            show-overflow-tooltip
        />
        <el-table-column
            prop="ruleCode"
            label="规则编码"
            min-width="140"
            show-overflow-tooltip
        />
        <el-table-column
            label="消息类型"
            width="110"
        >
          <template #default="{ row }">
            {{ messageTypeLabel(row.messageType) }}
          </template>
        </el-table-column>
        <el-table-column
            prop="levelCount"
            label="档位"
            width="70"
            align="center"
        />
        <el-table-column
            prop="productCount"
            label="产品"
            width="70"
            align="center"
        />
        <el-table-column
            label="错误策略"
            width="110"
        >
          <template #default="{ row }">
            {{ errorPolicyLabel(row.errorPolicy) }}
          </template>
        </el-table-column>
        <el-table-column
            prop="revision"
            label="修订"
            width="72"
            align="center"
        />
        <el-table-column
            label="状态"
            width="90"
            align="center"
        >
          <template #default="{ row }">
            <el-switch
                v-hasPermi="['iot:rule:edit']"
                :model-value="row.status === 1"
                :loading="statusChangingId === row.ruleId"
                :disabled="kind === 'WINDOW' && !capabilities.windowRule.activate && row.status !== 1"
                @change="value => changeStatus(row, value)"
            />
          </template>
        </el-table-column>
        <el-table-column
            label="操作"
            width="170"
            fixed="right"
        >
          <template #default="{ row }">
            <el-button
                v-hasPermi="['iot:rule:list']"
                link
                type="primary"
                size="small"
                @click="openEdit(row.ruleId, true)"
            >
              查看
            </el-button>
            <el-button
                v-hasPermi="['iot:rule:edit']"
                link
                type="primary"
                size="small"
                @click="openEdit(row.ruleId)"
            >
              编辑
            </el-button>
            <el-button
                v-hasPermi="['iot:rule:remove']"
                link
                type="danger"
                size="small"
                @click="removeRule(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="compact-pagination">
      <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          small
          background
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, prev, pager, next, sizes"
          @change="loadRules"
      />
    </div>

    <el-dialog
        v-model="dialogVisible"
        :title="dialogTitle"
        width="min(920px, 94vw)"
        :close-on-click-modal="false"
        align-center
        destroy-on-close
        class="compact-edit-dialog compact-edit-dialog-scrollable rule-edit-dialog"
        @closed="resetForm"
    >
      <el-form
          ref="formRef"
          :model="form"
          :rules="formRules"
          :disabled="readonly"
          label-width="92px"
          size="small"
          class="compact-edit-form rule-form"
      >
        <el-tabs
            v-model="activeTab"
            class="compact-tabs rule-tabs"
        >
          <el-tab-pane
              label="基础与监听"
              name="base"
          >
            <div class="grid grid-cols-1 gap-x-3 md:grid-cols-2">
              <el-form-item
                  label="规则编码"
                  prop="ruleCode"
                  :required="!editingId"
              >
                <el-input
                    v-model="form.ruleCode"
                    :disabled="Boolean(editingId)"
                    maxlength="50"
                />
              </el-form-item>
              <el-form-item
                  label="规则名称"
                  prop="ruleName"
                  required
              >
                <el-input
                    v-model="form.ruleName"
                    maxlength="120"
                />
              </el-form-item>
              <el-form-item
                  label="模型类型"
                  prop="messageType"
                  required
              >
                <el-select
                    v-model="form.messageType"
                    class="!w-full"
                >
                  <el-option
                      v-for="item in modelTypes"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                  />
                </el-select>
              </el-form-item>
              <el-form-item
                  label="错误策略"
                  required
              >
                <el-select
                    v-model="form.errorPolicy"
                    class="!w-full"
                >
                  <el-option
                      v-for="item in errorPolicies"
                      :key="item.code"
                      :label="item.name"
                      :value="item.code"
                  />
                </el-select>
              </el-form-item>
              <el-form-item
                  label="产品"
                  prop="productIds"
                  required
                  class="md:col-span-2"
              >
                <el-select
                    v-model="form.productIds"
                    multiple
                    filterable
                    collapse-tags
                    collapse-tags-tooltip
                    class="!w-full"
                    placeholder="选择规则作用的普通产品"
                >
                  <el-option
                      v-for="product in products"
                      :key="product.productId"
                      :label="`${product.productName} (${product.productKey})`"
                      :value="product.productId"
                  />
                </el-select>
              </el-form-item>
              <el-form-item
                  label="模型项"
                  prop="identifiers"
                  required
                  class="md:col-span-2"
              >
                <el-select
                    v-model="form.identifiers"
                    multiple
                    filterable
                    collapse-tags
                    collapse-tags-tooltip
                    :loading="modelItemsLoading"
                    :disabled="!form.productIds.length"
                    class="!w-full"
                    placeholder="从所选产品共有的属性或事件中选择"
                >
                  <el-option
                      v-for="item in modelItems"
                      :key="item.identifier"
                      :label="`${item.name} (${item.identifier})`"
                      :value="item.identifier"
                  />
                </el-select>
                <div class="mt-1 text-[11px] leading-4 text-gray-400">
                  多产品规则仅展示所有已选产品共同定义且类型兼容的模型项。
                </div>
              </el-form-item>
            </div>
          </el-tab-pane>

          <el-tab-pane
              :label="kind === 'INSTANT' ? '取值' : '窗口与聚合'"
              name="compute"
          >
            <div class="window-hint-row">
              <div class="window-hint">
                <div class="window-hint-title">
                  {{ kindMeta.name }}
                </div>
                <div>{{ kindMeta.help }}</div>
                <div class="window-hint-example">
                  示例：{{ kindMeta.example }}
                </div>
              </div>
            </div>
            <div class="grid grid-cols-1 gap-x-3 md:grid-cols-2">
              <template v-if="kind === 'WINDOW'">
                <el-form-item
                    label="窗口类型"
                    required
                >
                  <el-select
                      v-model="form.windowType"
                      class="!w-full"
                  >
                    <el-option
                        v-for="item in windowTypeOptions"
                        :key="item.code"
                        :label="item.name"
                        :value="item.code"
                    >
                      <div class="window-option">
                        <span>{{ item.name }}</span>
                        <span>{{ item.description }}</span>
                      </div>
                    </el-option>
                  </el-select>
                </el-form-item>
                <el-form-item
                    label="聚合类型"
                    required
                >
                  <el-select
                      v-model="form.aggregateType"
                      class="!w-full"
                      @change="normalizeAggregate"
                  >
                    <el-option
                        v-for="item in aggregateTypes"
                        :key="item"
                        :label="item"
                        :value="item"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item
                    label="时间模式"
                    required
                >
                  <el-select
                      v-model="form.timeMode"
                      class="!w-full"
                  >
                    <el-option
                        label="处理时间"
                        value="PROCESSING_TIME"
                    />
                    <el-option
                        label="事件时间"
                        value="EVENT_TIME"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item
                    label="状态范围"
                    required
                >
                  <el-select
                      v-model="form.stateScope"
                      class="!w-full"
                      disabled
                  >
                    <el-option
                        label="设备"
                        value="DEVICE"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item
                    label="窗口长度"
                    required
                >
                  <div class="duration-input">
                    <el-input-number
                        v-model="form.sizeValue"
                        :min="1"
                        controls-position="right"
                    />
                    <el-select v-model="form.sizeUnit">
                      <el-option
                          v-for="unit in durationUnits"
                          :key="unit.value"
                          :label="unit.label"
                          :value="unit.value"
                      />
                    </el-select>
                  </div>
                </el-form-item>
                <el-form-item
                    v-if="form.windowType === 'HOPPING_TIME'"
                    label="滑动步长"
                    required
                >
                  <div class="duration-input">
                    <el-input-number
                        v-model="form.advanceValue"
                        :min="1"
                        controls-position="right"
                    />
                    <el-select v-model="form.advanceUnit">
                      <el-option
                          v-for="unit in durationUnits"
                          :key="unit.value"
                          :label="unit.label"
                          :value="unit.value"
                      />
                    </el-select>
                  </div>
                </el-form-item>
                <el-form-item
                    v-if="form.timeMode === 'EVENT_TIME'"
                    label="宽限时间"
                    required
                >
                  <div class="duration-input">
                    <el-input-number
                        v-model="form.graceValue"
                        :min="0"
                        controls-position="right"
                    />
                    <el-select v-model="form.graceUnit">
                      <el-option
                          v-for="unit in durationUnits"
                          :key="unit.value"
                          :label="unit.label"
                          :value="unit.value"
                      />
                    </el-select>
                  </div>
                </el-form-item>
                <el-form-item
                    label="保留时间"
                    required
                >
                  <div class="duration-input">
                    <el-input-number
                        v-model="form.retentionValue"
                        :min="1"
                        controls-position="right"
                    />
                    <el-select v-model="form.retentionUnit">
                      <el-option
                          v-for="unit in durationUnits"
                          :key="unit.value"
                          :label="unit.label"
                          :value="unit.value"
                      />
                    </el-select>
                  </div>
                </el-form-item>
              </template>
              <template v-if="requiresValue">
                <el-form-item
                    class="md:col-span-2"
                    label="取值标识符"
                    prop="valueIdentifier"
                    required
                >
                  <div class="w-full">
                    <!-- 下拉而非手输：属性受后端 5048/5049 强校验，事件参数后端刻意不校验，
                         两种情况下手打错误的代价分别是「提交被拒」与「规则静默永不触发」 -->
                    <el-select
                        v-model="form.valueIdentifier"
                        class="!w-full"
                        filterable
                        :loading="modelItemsLoading"
                        :placeholder="valueOptions.length ? '选择取值' : emptyValueHint"
                    >
                      <el-option
                          v-for="option in valueOptions"
                          :key="option.identifier"
                          :value="option.identifier"
                          :label="`${option.name}（${option.identifier}）`"
                          :disabled="optionDisabled(option)"
                      >
                        <div class="flex items-center justify-between gap-4">
                          <span>{{ option.name }}（{{ option.identifier }}）</span>
                          <span class="text-[11px] text-gray-400">
                            {{ option.dataType || '未声明类型' }}
                          </span>
                        </div>
                      </el-option>
                    </el-select>
                    <div class="field-hint">
                      {{ valueSourceHint }}
                    </div>
                  </div>
                </el-form-item>
              </template>
              <div
                  v-else
                  class="md:col-span-2 field-hint"
              >
                {{ valueHint }}
              </div>
            </div>
          </el-tab-pane>

          <el-tab-pane name="levels">
            <template #label>
              <span>档位<span class="ml-1 text-[11px] text-gray-400">{{ form.levels.length }}</span></span>
            </template>
            <el-form-item
                v-if="kind === 'INSTANT'"
                label="输出模式"
                prop="emitMode"
                required
            >
              <el-radio-group v-model="form.emitMode">
                <el-radio value="LEVEL_TRANSITION">
                  档位跃迁时输出（告警）
                </el-radio>
                <el-radio value="EVERY_MATCH">
                  每条匹配都输出（加工转出）
                </el-radio>
              </el-radio-group>
            </el-form-item>
            <el-alert
                class="mb-2"
                type="info"
                :closable="false"
                :title="levelAlertTitle"
            />
            <div
                v-for="(level, index) in form.levels"
                :key="index"
                class="level-card"
            >
              <div class="level-card-head">
                <span class="level-card-title">档位 {{ index + 1 }}</span>
                <span class="level-card-note">severity 越小越严重，判档按其升序取第一个满足条件的档位</span>
                <el-button
                    v-if="!readonly"
                    link
                    type="danger"
                    size="small"
                    class="ml-auto"
                    :disabled="form.levels.length <= 1"
                    @click="removeLevel(index)"
                >
                  移除
                </el-button>
              </div>
              <div class="grid grid-cols-1 gap-x-3 md:grid-cols-2">
                <el-form-item
                    label="档位编码"
                    :prop="`levels.${index}.levelCode`"
                    :rules="levelCodeRule"
                    required
                >
                  <el-input
                      v-model="level.levelCode"
                      maxlength="30"
                      placeholder="如 CRITICAL"
                  />
                </el-form-item>
                <el-form-item
                    label="严重度"
                    :prop="`levels.${index}.severity`"
                    :rules="severityRule"
                    required
                >
                  <el-input-number
                      v-model="level.severity"
                      :min="0"
                      :max="32766"
                      controls-position="right"
                      class="!w-[150px]"
                  />
                </el-form-item>
                <el-form-item
                    v-if="kind === 'INSTANT'"
                    label="条件类型"
                    required
                >
                  <el-select
                      v-model="level.conditionKind"
                      class="!w-full"
                  >
                    <el-option
                        label="阈值比较"
                        value="THRESHOLD"
                    />
                    <el-option
                        label="Groovy 条件脚本"
                        value="SCRIPT"
                    />
                  </el-select>
                </el-form-item>
                <el-form-item label="冷却时长">
                  <el-input-number
                      v-model="level.cooldownMillis"
                      :min="0"
                      :step="1000"
                      :disabled="everyMatch"
                      controls-position="right"
                      class="!w-full"
                      placeholder="毫秒，留空不限流"
                  />
                </el-form-item>
                <el-form-item
                    label="Kafka 输出"
                    :prop="`levels.${index}.kafkaOutputIds`"
                    :rules="kafkaOutputIdsRule"
                    required
                    class="md:col-span-2"
                >
                  <el-select
                      v-model="level.kafkaOutputIds"
                      multiple
                      filterable
                      collapse-tags
                      collapse-tags-tooltip
                      class="!w-full"
                      placeholder="选择该档位绑定的 Kafka 输出"
                  >
                    <el-option
                        v-for="item in ruleOutputOptions"
                        :key="item.outputId"
                        :label="`${item.outputName} · ${item.targetTopic} · ${formatLabel(item.format)}`"
                        :value="item.outputId"
                    />
                  </el-select>
                </el-form-item>
                <template v-if="level.conditionKind === 'THRESHOLD'">
                  <el-form-item
                      label="比较符"
                      required
                  >
                    <el-select
                        v-model="level.operator"
                        class="!w-full"
                    >
                      <el-option
                          v-for="item in operatorOptions"
                          :key="item.code"
                          :label="item.name"
                          :value="item.code"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item
                      label="阈值"
                      :prop="`levels.${index}.threshold`"
                      :rules="thresholdRule"
                      required
                  >
                    <el-input-number
                        v-model="level.threshold"
                        controls-position="right"
                        class="!w-full"
                    />
                  </el-form-item>
                </template>
                <el-form-item
                    v-else
                    label="条件脚本"
                    :prop="`levels.${index}.conditionScript`"
                    :rules="conditionScriptRule"
                    required
                    class="md:col-span-2"
                >
                  <el-input
                      v-model="level.conditionScript"
                      type="textarea"
                      :rows="3"
                      input-style="font-family: ui-monospace, monospace"
                      placeholder="返回 Boolean，例如：ctx.numberValue('temperature', -999d) > 80d"
                  />
                </el-form-item>
                <el-form-item
                    label="输出脚本"
                    :prop="`levels.${index}.outputScript`"
                    :rules="outputScriptRule"
                    required
                    class="md:col-span-2"
                >
                  <el-input
                      v-model="level.outputScript"
                      type="textarea"
                      :rows="4"
                      input-style="font-family: ui-monospace, monospace"
                      placeholder="[type: 'temperature_high', level: trigger.levelCode(), value: trigger.avg()]"
                  />
                </el-form-item>
              </div>
            </div>
            <el-button
                v-if="!readonly"
                size="small"
                :disabled="form.levels.length >= 10"
                @click="addLevel"
            >
              <el-icon>
                <Plus/>
              </el-icon>
              新增档位
            </el-button>
            <div class="script-help mt-2">
              <div>
                <span class="script-help-title">输出脚本入参</span>
                <code>ctx</code>：消息上下文；<code>trigger</code>：本次跃迁与聚合结果。
              </div>
              <div>
                跃迁信息：<code>trigger.fireType()</code>（<code>LEVEL_RAISE</code> / <code>LEVEL_LOWER</code> /
                <code>RECOVERY</code>）、<code>levelCode()</code>、<code>severity()</code>、
                <code>fromLevelCode()</code>、<code>raised()</code>、<code>recovered()</code>、
                <code>triggeredAt()</code>。
              </div>
              <div>
                窗口信息：<code>windowed()</code>、<code>windowStart()</code>、<code>windowEnd()</code>、
                <code>windowMillis()</code>；聚合结果 <code>count()</code>、<code>sum()</code>、
                <code>min()</code>、<code>max()</code>、<code>avg()</code>。
              </div>
              <div>
                <span class="script-help-title">返回</span>必须返回字符串键的
                <code>Map&lt;String, Object&gt;</code>；平台补齐消息、规则、设备与窗口信封，脚本只负责业务
                <code>payload</code>。
              </div>
              <div>恢复（回落到正常）时执行的是<b>此前已宣告档位</b>的输出脚本 —— 下游据此知道是哪一条告警恢复了。</div>
            </div>
          </el-tab-pane>

          <el-tab-pane
              label="过滤脚本"
              name="filter"
          >
            <el-alert
                class="mb-2"
                type="info"
                :closable="false"
                title="过滤脚本是纯闸门，只回答「这条消息与本规则相关吗」；告警条件由档位表达。留空表示全部通过。"
            />
            <el-form-item label="过滤脚本">
              <div class="w-full">
                <el-input
                    v-model="form.filterScript"
                    type="textarea"
                    :rows="8"
                    input-style="font-family: ui-monospace, monospace"
                    placeholder="可选；例如：ctx.deviceCode().startsWith('LINE-A')"
                />
                <div class="script-help">
                  <div><span class="script-help-title">入参</span><code>ctx</code>：当前消息的只读上下文。</div>
                  <div>
                    常用访问器：
                    <code>ctx.productKey()</code>、<code>ctx.deviceCode()</code>、<code>ctx.occurredAt()</code>、
                    <code>ctx.messageType()</code>、<code>ctx.identifier()</code>。
                  </div>
                  <div>
                    属性消息：
                    <code>numberValue(id, 默认值)</code>、<code>stringValue(id)</code>、
                    <code>boolValue(id, 默认值)</code>、<code>hasValue(id)</code>。
                    事件消息使用对应的 <code>numberParam</code>、<code>stringParam</code>、
                    <code>boolParam</code>、<code>hasParam</code>。
                  </div>
                  <div>
                    快照：
                    <code>ctx.product()</code>、<code>ctx.device()</code>、<code>ctx.thingModel()</code>；
                    还可使用 <code>ctx.ageMillis()</code> 和 <code>ctx.between(value, low, high)</code>。
                  </div>
                  <div>
                    <span class="script-help-title">返回</span>必须严格返回 <code>Boolean</code>，不接受数字、字符串、集合或
                    <code>null</code>。
                  </div>
                </div>
              </div>
            </el-form-item>
          </el-tab-pane>
        </el-tabs>
      </el-form>
      <template #footer>
        <div class="flex justify-end gap-2">
          <el-button
              size="small"
              @click="dialogVisible = false"
          >
            {{ readonly ? '关闭' : '取消' }}
          </el-button>
          <el-button
              v-if="!readonly"
              v-hasPermi="['iot:rule:add']"
              size="small"
              :loading="validating"
              @click="tryValidate"
          >
            校验配置
          </el-button>
          <el-button
              v-if="!readonly"
              v-hasPermi="['iot:rule:edit', 'iot:rule:add']"
              type="primary"
              size="small"
              :loading="saving"
              @click="save"
          >
            保存
          </el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import type {FormInstance, FormItemRule, FormRules} from 'element-plus'
import {ElMessage, ElMessageBox} from 'element-plus'
import {getErrorMessage} from '@/utils/error'
import {
  listProducts,
  listTmEvents,
  listTmProperties,
  type ProductVO,
  type TmEventVO,
  type TmPropertyVO
} from '@/api/device'
import {
  type AggregateType,
  changeRuleStatus,
  type ConditionKind,
  createRule,
  deleteRule,
  type EmitMode,
  type ErrorPolicy,
  getRule,
  getRuleCapabilities,
  type InstantRuleSaveRequest,
  listRules,
  type MessageType,
  type RuleKind,
  type RuleLevelRequest,
  type RuleVO,
  type ThresholdOperator,
  type TimeMode,
  updateRule,
  validateRule,
  type ValueSource,
  type WindowRuleSaveRequest,
  type WindowType,
} from '@/api/rule'
import {
  type KafkaOutputVO,
  listKafkaOutputs,
  type OutputFormat,
} from '@/api/ruleKafkaOutput'

type DurationUnit = 'SECOND' | 'MINUTE' | 'HOUR'
type CodeName<T extends string = string> = { code: T; name: string }

type LevelForm = {
  levelCode: string
  severity: number
  conditionKind: ConditionKind
  operator: ThresholdOperator
  threshold?: number
  conditionScript: string
  outputScript: string
  cooldownMillis?: number
  kafkaOutputIds: number[]
}

type RuleForm = {
  ruleCode: string; ruleName: string; messageType: MessageType; productIds: number[]; identifiers: string[]
  errorPolicy: ErrorPolicy
  emitMode: EmitMode
  /**
   * 即时规则的 valueConfig 与窗口规则的 aggregateConfig 共用它：两者都在回答「取哪个字段的数」。
   * 配套的 valueSource **不在表单里** —— 它由 messageType 唯一确定，见下方同名 computed。
   */
  valueIdentifier: string
  windowType: WindowType; timeMode: TimeMode; stateScope: 'DEVICE'
  sizeValue?: number; sizeUnit: DurationUnit
  advanceValue?: number; advanceUnit: DurationUnit
  graceValue?: number; graceUnit: DurationUnit
  retentionValue?: number; retentionUnit: DurationUnit
  aggregateType: AggregateType
  filterScript: string
  levels: LevelForm[]
  version?: number
}

/** 取值下拉的一个候选：属性本身，或某个被监听事件的一个输出参数 */
type ValueOption = { identifier: string; name: string; dataType?: string }

/** 跨全部所选产品取交集后的模型项；事件项带着自己的输出参数，取值下拉要用 */
type ModelItem = ValueOption & { params: ValueOption[] }

/**
 * 阈值比较只接受数值型（后端 `IDENTIFIER_TYPE_MISMATCH` 5049）。
 * 与 `RuleConfigValidator.checkNumericProperty` 依据的物模型 `data_type` 取值域保持一致。
 */
const NUMERIC_DATA_TYPES = new Set(['int', 'float', 'double'])

const modelTypes: Array<{ label: string; value: MessageType }> = [
  {label: '属性', value: 'property'},
  {label: '事件', value: 'event'},
]
const errorPolicies: Array<CodeName<ErrorPolicy>> = [
  {code: 'DLQ_MESSAGE', name: '消息进 DLQ'},
  {code: 'SKIP_RULE', name: '跳过本规则'},
  {code: 'DROP_MESSAGE', name: '丢弃消息'},
]
const windowTypeOptions: Array<{ code: WindowType; name: string; description: string }> = [
  {code: 'TUMBLING_TIME', name: '固定时间窗口', description: '固定周期，窗口互不重叠'},
  {code: 'HOPPING_TIME', name: '滑动时间窗口', description: '固定长度，按步长滚动刷新'},
]
const durationUnits: Array<{ label: string; value: DurationUnit; multiplier: number }> = [
  {label: '秒', value: 'SECOND', multiplier: 1000},
  {label: '分钟', value: 'MINUTE', multiplier: 60000},
  {label: '小时', value: 'HOUR', multiplier: 3600000},
]
const aggregateTypes: AggregateType[] = ['COUNT', 'SUM', 'MIN', 'MAX', 'AVG', 'FIRST', 'LAST', 'CHANGE_RATE']
const operatorOptions: Array<CodeName<ThresholdOperator>> = [
  {code: 'GT', name: '大于（>）'},
  {code: 'GTE', name: '大于等于（≥）'},
  {code: 'LT', name: '小于（<）'},
  {code: 'LTE', name: '小于等于（≤）'},
  {code: 'EQ', name: '等于（=）'},
  {code: 'NE', name: '不等于（≠）'},
]
const kindMetas: Record<RuleKind, { name: string; help: string; example: string; hint: string }> = {
  INSTANT: {
    name: '即时规则',
    help: '每条通过过滤的消息独立判档，不累计历史数据，因此不占用任何窗口状态。',
    example: '温度超过 80℃ 判为危急、超过 60℃ 判为预警，回落到 60℃ 以下即恢复。',
    hint: '逐条消息判档，无状态',
  },
  WINDOW: {
    name: '窗口规则',
    help: '按设备把消息累积进时间窗口，窗口关闭时用聚合结果判档。',
    example: '每 5 分钟的平均值超过 80 判为危急，低于阈值即恢复。',
    hint: '窗口关闭时用聚合结果判档',
  },
}

const DEFAULT_OUTPUT_CODE = 'system-kafka-default'
const ruleOutputOptions = ref<KafkaOutputVO[]>([])

function defaultKafkaOutputIds(): number[] {
  const fallback = ruleOutputOptions.value.find(item => item.outputCode === DEFAULT_OUTPUT_CODE)
  return fallback ? [fallback.outputId] : []
}

const emptyLevel = (): LevelForm => ({
  levelCode: '', severity: 10, conditionKind: 'THRESHOLD', operator: 'GT',
  conditionScript: '', outputScript: 'return [:]',
  kafkaOutputIds: defaultKafkaOutputIds(),
})

const emptyForm = (): RuleForm => ({
  ruleCode: '', ruleName: '', messageType: 'property', productIds: [], identifiers: [],
  errorPolicy: 'DLQ_MESSAGE',
  emitMode: 'LEVEL_TRANSITION',
  valueIdentifier: '',
  windowType: 'TUMBLING_TIME', timeMode: 'PROCESSING_TIME', stateScope: 'DEVICE',
  sizeValue: 5, sizeUnit: 'MINUTE', advanceValue: 1, advanceUnit: 'MINUTE',
  graceValue: 0, graceUnit: 'SECOND', retentionValue: 5, retentionUnit: 'MINUTE',
  aggregateType: 'AVG',
  filterScript: '',
  levels: [emptyLevel()],
})

const kind = ref<RuleKind>('INSTANT')
const query = reactive<{ keyword?: string; messageType?: MessageType; status?: number }>({})
const rows = ref<RuleVO[]>([])
const products = ref<ProductVO[]>([])
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const validating = ref(false)
const modelItemsLoading = ref(false)
const modelItems = ref<ModelItem[]>([])
let modelLoadToken = 0
const statusChangingId = ref<number>()
const capabilities = reactive({
  instantRule: {configure: true, activate: true, runtimeInstalled: true},
  windowRule: {configure: true, activate: false, runtimeInstalled: false},
})
const dialogVisible = ref(false)
const editingId = ref<number>()
const readonly = ref(false)
const activeTab = ref('base')
const formRef = ref<FormInstance>()
const form = reactive<RuleForm>(emptyForm())

const kindMeta = computed(() => kindMetas[kind.value])
const kindHint = computed(() => kindMeta.value.hint)
const everyMatch = computed(() => kind.value === 'INSTANT' && form.emitMode === 'EVERY_MATCH')
const levelAlertTitle = computed(() => everyMatch.value
    ? '每条通过过滤且命中档位的消息都会执行输出脚本并写入绑定 Topic；不维护档位状态，也不输出恢复。冷却不可用。'
    : '同一 (规则, 设备) 任一时刻只处于一个档位。输出只在档位变化时产生：升档、降档、回落到正常各输出一次；持续停留在同一档不再输出。')
const dialogTitle = computed(() => {
  const noun = kind.value === 'INSTANT' ? '即时规则' : '窗口规则'
  return readonly.value ? `查看${noun}` : editingId.value ? `编辑${noun}` : `新建${noun}`
})

/**
 * 窗口规则的取值由聚合类型决定（COUNT 只数条数）；
 * 即时规则只要存在阈值档位就必须知道拿哪个字段比较。
 */
const requiresValue = computed(() => kind.value === 'WINDOW'
    ? form.aggregateType !== 'COUNT'
    : form.levels.some(level => level.conditionKind === 'THRESHOLD'))
const valueHint = computed(() => kind.value === 'WINDOW'
    ? 'COUNT 只统计条数，不需要取值配置。'
    : '全部档位都用条件脚本时不需要取值配置 —— 比较逻辑整个在脚本里。')

/**
 * 取值来源由模型类型唯一确定，不接受用户选择：属性消息没有事件参数、事件消息没有属性值，
 * 不一致的组合运行期恒取不到值，规则会静默永不触发（后端 RuleConfigValidator 同样拒绝）。
 */
const valueSource = computed<ValueSource>(() =>
    form.messageType === 'event' ? 'EVENT_PARAM' : 'PROPERTY')

/**
 * 取值候选：属性规则取产品属性，事件规则取<b>被监听事件</b>的输出参数
 * （同名参数按 identifier 去重 —— 多个事件带同名参数时是同一个取值）。
 *
 * <p>事件参数这一支尤其必要：后端**刻意不查物模型**校验事件参数
 * （`rule-config-schema.md` §五：参数类型定义在事件定义里，用属性视图去查会误拒合法规则），
 * 因此参数名打错时前后端都不会报错，规则会静默永不触发。下拉是这条路径上唯一的防线。
 */
const valueOptions = computed<ValueOption[]>(() => {
  if (form.messageType !== 'event') {
    return modelItems.value.map(({identifier, name, dataType}) => ({identifier, name, dataType}))
  }
  const listened = new Set(form.identifiers)
  const merged = new Map<string, ValueOption>()
  modelItems.value
      .filter(item => listened.has(item.identifier))
      .forEach(item => item.params.forEach(param => merged.set(param.identifier, param)))
  return [...merged.values()]
})

/**
 * 已声明为非数值型的选项画灰：本下拉只在 {@link requiresValue} 为真时渲染，
 * 而那恰好等价于「这个值要么参与阈值比较、要么参与聚合」—— 两者都只接受数值型。
 * 属性选了会被后端 5049 拒绝；事件参数后端不校验，但运行期 `numberParam` 取不到值，
 * 规则会静默永不触发，所以这里同样拦。
 *
 * <p><b>未声明类型的不拦</b>：事件参数的 dataType 来自事件定义，可能缺失。
 * 此时无从判断，而后端也不判 —— 画灰会把一条后端认可的规则堵死在界面上。
 */
function optionDisabled(option: ValueOption): boolean {
  return option.dataType != null && !NUMERIC_DATA_TYPES.has(option.dataType)
}

const valueSourceHint = computed(() =>
    '值来源随模型类型自动确定，当前取自'
    + (valueSource.value === 'EVENT_PARAM' ? '所监听事件的参数' : '设备上报的属性')
    + '；只接受 int / float / double')

const emptyValueHint = computed(() => form.messageType === 'event'
    ? '先在「基础与监听」里选择事件，这里才会列出它的输出参数'
    : '先在「基础与监听」里选择产品，这里才会列出可用属性')

const formRules: FormRules = {
  ruleCode: [{
    validator: (_rule, value, callback) => editingId.value || String(value || '').trim()
        ? callback() : callback(new Error('规则编码不能为空')), trigger: 'blur'
  }],
  ruleName: [{required: true, message: '规则名称不能为空', trigger: 'blur'}],
  messageType: [{required: true, message: '模型类型不能为空', trigger: 'change'}],
  productIds: [{type: 'array', required: true, min: 1, message: '至少选择一个产品', trigger: 'change'}],
  identifiers: [{type: 'array', required: true, min: 1, message: '至少选择一个属性或事件', trigger: 'change'}],
  emitMode: [{required: true, message: '输出模式不能为空', trigger: 'change'}],
  // 条件必填：仅在「需要取值」时生效，与红星的显隐条件同一个判据
  valueIdentifier: [{
    validator: (_rule, value, callback) => !requiresValue.value || String(value || '').trim()
        ? callback()
        : callback(new Error(kind.value === 'WINDOW'
            ? `聚合类型 ${form.aggregateType} 必须指定取值标识符`
            : '存在阈值档位时必须指定被监控的取值标识符')), trigger: 'change'
  }],
}

/**
 * prop → 所属 tab。校验失败时把用户带到出错的那一页 ——
 * 否则错误红字画在一个没打开的 tab 里，表现为「点了保存没反应」。
 */
const TAB_BY_PROP: Array<[RegExp, string]> = [
  [/^valueIdentifier$/, 'compute'],
  [/^emitMode$/, 'levels'],
  [/^levels\./, 'levels'],
  [/^filterScript$/, 'filter'],
]

const levelCodeRule: FormItemRule[] = [
  {required: true, message: '档位编码不能为空', trigger: 'blur'},
  {
    validator: (_rule, value, callback) => {
      const code = String(value || '').trim()
      const duplicated = form.levels.filter(level => level.levelCode.trim() === code).length > 1
      callback(duplicated ? new Error('同一规则内档位编码必须唯一') : undefined)
    }, trigger: 'blur'
  },
]
const severityRule: FormItemRule[] = [
  {required: true, message: '严重度不能为空', trigger: 'change'},
  {
    validator: (_rule, value, callback) => {
      const duplicated = form.levels.filter(level => level.severity === value).length > 1
      callback(duplicated ? new Error('同一规则内 severity 必须唯一') : undefined)
    }, trigger: 'change'
  },
]
const thresholdRule: FormItemRule[] = [{required: true, message: '阈值不能为空', trigger: 'change'}]
const conditionScriptRule: FormItemRule[] = [{required: true, message: '条件脚本不能为空', trigger: 'blur'}]
const outputScriptRule: FormItemRule[] = [{required: true, message: '输出脚本不能为空', trigger: 'blur'}]
const kafkaOutputIdsRule: FormItemRule[] = [
  {type: 'array', required: true, min: 1, message: '至少绑定一个 Kafka 输出', trigger: 'change'},
]

function formatLabel(value: OutputFormat): string {
  return value === 'MESSAGEPACK' ? 'MessagePack' : value
}

function levelKafkaOutputIds(level: { kafkaOutputIds?: number[]; kafkaOutputs?: KafkaOutputVO[] }): number[] {
  if (level.kafkaOutputIds?.length) return [...level.kafkaOutputIds]
  return (level.kafkaOutputs || []).map(item => item.outputId)
}

function messageTypeLabel(value: MessageType): string {
  return modelTypes.find(item => item.value === value)?.label || value
}

function errorPolicyLabel(value: ErrorPolicy): string {
  return errorPolicies.find(item => item.code === value)?.name || value
}

function addLevel() {
  const maxSeverity = form.levels.reduce((max, level) => Math.max(max, level.severity), 0)
  form.levels.push({...emptyLevel(), severity: Math.min(maxSeverity + 10, 32766)})
}

function removeLevel(index: number) {
  form.levels.splice(index, 1)
}

/**
 * COUNT 不取值，验证器会因为多余的 valueIdentifier 直接拒绝保存，这里同步清掉。
 */
function normalizeAggregate() {
  if (form.aggregateType === 'COUNT') {
    form.valueIdentifier = ''
  }
}

function toMillis(value: number | undefined, unit: DurationUnit): number | undefined {
  if (value === undefined) return undefined
  const multiplier = durationUnits.find(item => item.value === unit)?.multiplier || 1
  return value * multiplier
}

function fromMillis(value: unknown): { value?: number; unit: DurationUnit } {
  if (typeof value !== 'number') return {value: undefined, unit: 'SECOND'}
  for (const unit of [...durationUnits].reverse()) {
    if (value % unit.multiplier === 0) return {value: value / unit.multiplier, unit: unit.value}
  }
  return {value: value / 1000, unit: 'SECOND'}
}

function buildLevels(): RuleLevelRequest[] {
  return form.levels.map(level => ({
    levelCode: level.levelCode.trim(),
    severity: level.severity,
    conditionKind: level.conditionKind,
    thresholdConfig: level.conditionKind === 'THRESHOLD'
        ? {operator: level.operator, threshold: level.threshold} : undefined,
    conditionScript: level.conditionKind === 'SCRIPT' ? level.conditionScript.trim() : undefined,
    outputScript: level.outputScript,
    cooldownMillis: everyMatch.value ? undefined : (level.cooldownMillis || undefined),
    kafkaOutputIds: [...level.kafkaOutputIds],
  }))
}

function buildInstantRequest(): InstantRuleSaveRequest {
  return {
    ...buildCommon(),
    emitMode: form.emitMode,
    valueConfig: requiresValue.value
        ? {valueIdentifier: form.valueIdentifier.trim(), valueSource: valueSource.value} : undefined,
  }
}

function buildWindowRequest(): WindowRuleSaveRequest {
  return {
    ...buildCommon(),
    windowConfig: {
      type: form.windowType,
      timeMode: form.timeMode,
      stateScope: form.stateScope,
      sizeMillis: toMillis(form.sizeValue, form.sizeUnit),
      advanceMillis: form.windowType === 'HOPPING_TIME'
          ? toMillis(form.advanceValue, form.advanceUnit) : undefined,
      graceMillis: form.timeMode === 'EVENT_TIME' ? toMillis(form.graceValue, form.graceUnit) : undefined,
      retentionMillis: toMillis(form.retentionValue, form.retentionUnit),
    },
    aggregateConfig: {
      type: form.aggregateType,
      valueIdentifier: requiresValue.value ? form.valueIdentifier.trim() : undefined,
      valueSource: requiresValue.value ? valueSource.value : undefined,
    },
  }
}

function buildCommon() {
  return {
    ruleCode: editingId.value ? undefined : form.ruleCode.trim(),
    ruleName: form.ruleName.trim(),
    messageType: form.messageType,
    productIds: form.productIds,
    listenerConfig: {identifiers: [...new Set(form.identifiers)]},
    filterScript: form.filterScript.trim() || undefined,
    levels: buildLevels(),
    errorPolicy: form.errorPolicy,
    version: form.version,
  }
}

async function loadRules() {
  loading.value = true
  try {
    const response = await listRules(kind.value, {pageNum: pageNum.value, pageSize: pageSize.value, query})
    rows.value = response.data?.list || []
    total.value = response.data?.total || 0
  } catch (error: unknown) {
    console.error('加载规则列表失败', error)
    ElMessage.error(getErrorMessage(error, '加载规则列表失败'))
  } finally {
    loading.value = false
  }
}

async function loadProducts() {
  const response = await listProducts({pageNum: 1, pageSize: 1000, query: {productType: 1}})
  products.value = response.data?.list || []
}

async function loadRuleOutputs() {
  try {
    const response = await listKafkaOutputs({
      pageNum: 1,
      pageSize: 1000,
      query: {purpose: 'RULE_OUTPUT'},
    })
    ruleOutputOptions.value = response.data?.list || []
  } catch (error: unknown) {
    console.error('加载 Kafka 输出定义失败', error)
    ElMessage.error(getErrorMessage(error, '加载 Kafka 输出定义失败'))
  }
}

function fillDefaultOutputs() {
  const defaults = defaultKafkaOutputIds()
  if (!defaults.length) return
  form.levels.forEach(level => {
    if (!level.kafkaOutputIds.length) level.kafkaOutputIds = [...defaults]
  })
}

function switchKind() {
  pageNum.value = 1
  loadRules()
}

function search() {
  pageNum.value = 1
  loadRules()
}

function resetQuery() {
  Object.assign(query, {keyword: undefined, messageType: undefined, status: undefined})
  search()
}

async function openCreate() {
  resetForm()
  dialogVisible.value = true
  await Promise.all([loadProducts(), loadRuleOutputs()])
  fillDefaultOutputs()
}

async function openEdit(ruleId: number, viewOnly = false) {
  resetForm()
  editingId.value = ruleId
  readonly.value = viewOnly
  dialogVisible.value = true
  try {
    const [detailResponse] = await Promise.all([
      getRule(kind.value, ruleId),
      loadProducts(),
      loadRuleOutputs(),
    ])
    const detail = detailResponse.data
    if (!detail) return
    const window = detail.windowConfig
    const aggregate = detail.aggregateConfig
    const value = detail.valueConfig
    const size = fromMillis(window?.sizeMillis)
    const advance = fromMillis(window?.advanceMillis)
    const grace = fromMillis(window?.graceMillis)
    const retention = fromMillis(window?.retentionMillis)
    Object.assign(form, {
      ruleCode: detail.ruleCode,
      ruleName: detail.ruleName,
      messageType: detail.messageType,
      errorPolicy: detail.errorPolicy,
      emitMode: detail.emitMode || 'LEVEL_TRANSITION',
      version: detail.version,
      filterScript: detail.filterScript === 'return true' ? '' : detail.filterScript,
      productIds: (detail.products || []).map(item => item.productId),
      identifiers: detail.listenerConfig?.identifiers || [],
      valueIdentifier: aggregate?.valueIdentifier || value?.valueIdentifier || '',
      windowType: window?.type || 'TUMBLING_TIME',
      timeMode: window?.timeMode || 'PROCESSING_TIME',
      stateScope: window?.stateScope || 'DEVICE',
      sizeValue: size.value, sizeUnit: size.unit,
      advanceValue: advance.value, advanceUnit: advance.unit,
      graceValue: grace.value ?? 0, graceUnit: grace.unit,
      retentionValue: retention.value, retentionUnit: retention.unit,
      aggregateType: aggregate?.type || 'AVG',
      levels: (detail.levels || []).map(level => ({
        levelCode: level.levelCode,
        severity: level.severity,
        conditionKind: level.conditionKind,
        operator: level.thresholdConfig?.operator || 'GT',
        threshold: level.thresholdConfig?.threshold,
        conditionScript: level.conditionScript || '',
        outputScript: level.outputScript,
        cooldownMillis: level.cooldownMillis,
        kafkaOutputIds: levelKafkaOutputIds(level),
      })),
    })
    if (!form.levels.length) form.levels.push(emptyLevel())
  } catch (error: unknown) {
    console.error('加载规则详情失败', error)
    ElMessage.error(getErrorMessage(error, '加载规则详情失败'))
  }
}

async function submit(action: 'validate' | 'save') {
  if (!await validateForm()) return false
  if (!validateConditional()) return false
  if (kind.value === 'INSTANT') {
    const request = buildInstantRequest()
    if (action === 'validate') {
      await validateRule('INSTANT', request)
      return true
    }
    if (editingId.value) {
      await updateRule('INSTANT', editingId.value, request)
    } else {
      await validateRule('INSTANT', request)
      await createRule('INSTANT', request)
    }
    return true
  }
  const request = buildWindowRequest()
  if (action === 'validate') {
    await validateRule('WINDOW', request)
    return true
  }
  if (editingId.value) {
    await updateRule('WINDOW', editingId.value, request)
  } else {
    await validateRule('WINDOW', request)
    await createRule('WINDOW', request)
  }
  return true
}

async function tryValidate() {
  validating.value = true
  try {
    if (await submit('validate')) ElMessage.success('配置与脚本校验通过')
  } catch (error: unknown) {
    console.error('规则校验失败', error)
    ElMessage.error(getErrorMessage(error, '规则校验失败'))
  } finally {
    validating.value = false
  }
}

async function save() {
  saving.value = true
  try {
    if (!await submit('save')) return
    ElMessage.success('规则已保存')
    dialogVisible.value = false
    await loadRules()
  } catch (error: unknown) {
    console.error('保存规则失败', error)
    ElMessage.error(getErrorMessage(error, '保存规则失败'))
  } finally {
    saving.value = false
  }
}

async function changeStatus(row: RuleVO, value: string | number | boolean) {
  if (kind.value === 'WINDOW' && value && !capabilities.windowRule.activate) {
    ElMessage.warning('当前部署未安装窗口规则运行时，只能查看、新建和修改')
    return
  }
  statusChangingId.value = row.ruleId
  try {
    await changeRuleStatus(kind.value, row.ruleId, value ? 1 : 0, row.version)
    ElMessage.success(value ? '规则已启用' : '规则已停用')
    await loadRules()
  } catch (error: unknown) {
    console.error('规则启停失败', error)
    ElMessage.error(getErrorMessage(error, '规则启停失败'))
  } finally {
    statusChangingId.value = undefined
  }
}

async function removeRule(row: RuleVO) {
  await ElMessageBox.confirm(`确定删除规则「${row.ruleName}」？`, '删除确认', {type: 'warning'})
  try {
    await deleteRule(kind.value, row.ruleId)
    ElMessage.success('规则已删除')
    await loadRules()
  } catch (error: unknown) {
    console.error('删除规则失败', error)
    ElMessage.error(getErrorMessage(error, '删除规则失败'))
  }
}

function resetForm() {
  editingId.value = undefined
  readonly.value = false
  activeTab.value = 'base'
  Object.assign(form, emptyForm())
  formRef.value?.clearValidate()
}

/**
 * 表单规则表达不了的跨字段约束。与后端 RuleConfigValidator 保持同口径 ——
 * 前端先拦一遍是为了把错误定位到具体页签，后端仍是唯一裁决方。
 */
/**
 * 表单级校验：失败时切到出错字段所在的 tab。
 * 取值标识符为空由 {@link formRules} 的字段级规则负责（红字定位到控件），
 * 这里只补「把用户带到那一页」，不再重复判一遍空值。
 */
async function validateForm(): Promise<boolean> {
  try {
    await formRef.value?.validate()
    return true
  } catch (error: unknown) {
    const invalidProp = Object.keys((error || {}) as Record<string, unknown>)[0]
    if (invalidProp) {
      activeTab.value = TAB_BY_PROP.find(([pattern]) => pattern.test(invalidProp))?.[1] || 'base'
    }
    return false
  }
}

function validateConditional() {
  if (kind.value === 'WINDOW') {
    const sizeMillis = toMillis(form.sizeValue, form.sizeUnit)
    const retentionMillis = toMillis(form.retentionValue, form.retentionUnit)
    if (!sizeMillis) return fieldError('compute', '窗口长度不能为空')
    if (!retentionMillis) return fieldError('compute', '保留时间不能为空')
    const grace = form.timeMode === 'EVENT_TIME' ? toMillis(form.graceValue, form.graceUnit) : 0
    if (form.timeMode === 'EVENT_TIME' && grace === undefined) {
      return fieldError('compute', '事件时间必须配置宽限时间')
    }
    if (retentionMillis < sizeMillis + (grace || 0)) {
      return fieldError('compute', '保留时间必须大于等于窗口长度与宽限时间之和')
    }
    if (form.windowType === 'HOPPING_TIME') {
      const advanceMillis = toMillis(form.advanceValue, form.advanceUnit)
      if (!advanceMillis) return fieldError('compute', '滑动步长不能为空')
      if (advanceMillis < 10000) return fieldError('compute', '滑动步长不能小于 10 秒')
      if (advanceMillis > sizeMillis) return fieldError('compute', '滑动步长不能大于窗口长度')
    }
  }
  return true
}

function fieldError(tab: string, message: string) {
  activeTab.value = tab
  ElMessage.error(message)
  return false
}

async function loadModelItems() {
  const token = ++modelLoadToken
  if (!form.productIds.length) {
    modelItems.value = []
    form.identifiers = []
    // 没有产品就没有物模型，取值也随之失去依据；这里显式清空而不是交给 pruneValueIdentifier ——
    // 它在候选为空时一律跳过，正是为了不误伤编辑态的回填
    form.valueIdentifier = ''
    return
  }
  modelItemsLoading.value = true
  try {
    const responses = await Promise.all(form.productIds.map(productId =>
        form.messageType === 'property' ? listTmProperties(productId) : listTmEvents(productId)))
    if (token !== modelLoadToken) return
    const itemSets = responses.map(response => {
      const items = (response.data || []) as Array<TmPropertyVO | TmEventVO>
      return new Map(items.map(item => {
        const params = 'inputParams' in item ? toEventParams(item.inputParams)
            : 'outputParams' in item ? toEventParams(item.outputParams) : []
        return [
          item.identifier,
          {
            identifier: item.identifier,
            name: 'propertyName' in item ? item.propertyName : item.eventName,
            dataType: 'dataType' in item ? item.dataType : undefined,
            params,
            // 跨产品比对的是「结构」而不是「有没有这个名字」：同名不同类型的属性
            // 用同一条规则去比阈值会在一部分产品上得出错误结论
            signature: 'dataType' in item ? item.dataType : JSON.stringify(params),
          },
        ]
      }))
    })
    const [first, ...rest] = itemSets
    modelItems.value = [...(first?.values() || [])]
        .filter(item => rest.every(items => items.get(item.identifier)?.signature === item.signature))
        .map(({identifier, name, dataType, params}) => ({identifier, name, dataType, params}))
    const available = new Set(modelItems.value.map(item => item.identifier))
    form.identifiers = form.identifiers.filter(identifier => available.has(identifier))
    pruneValueIdentifier()
  } catch (error: unknown) {
    console.error('加载产品模型项失败', error)
  } finally {
    if (token === modelLoadToken) modelItemsLoading.value = false
  }
}

/**
 * 事件参数定义（`us_iot_tm_event.input_params`）是 `Record<string, unknown>[]`，
 * 这里收敛成取值下拉需要的三元组；非法条目直接丢弃而不是显示成空白选项。
 */
function toEventParams(raw: Record<string, unknown>[] | undefined): ValueOption[] {
  return (raw || [])
      .map(param => ({
        identifier: String(param.identifier ?? ''),
        name: String(param.name ?? param.identifier ?? ''),
        dataType: param.dataType == null ? undefined : String(param.dataType),
      }))
      .filter(param => param.identifier)
}

/**
 * 所选模型项已经不提供当前取值标识符时清空它 —— 留着会让用户以为配好了，
 * 而后端要么以 5048 拒绝（属性），要么根本不校验（事件参数）后在运行期静默取不到值
 */
function pruneValueIdentifier() {
  // **候选还没加载完就不能裁剪**：编辑态填表会先触发 identifiers 变化、后拿到物模型，
  // 此刻 valueOptions 恒为空，照裁不误会把已保存的取值清成空白
  if (modelItemsLoading.value || !modelItems.value.length) {
    return
  }
  if (form.valueIdentifier
      && !valueOptions.value.some(option => option.identifier === form.valueIdentifier)) {
    form.valueIdentifier = ''
  }
}

watch([() => form.messageType, () => [...form.productIds]], loadModelItems)
// 事件规则的取值来自「被监听事件」的参数，改监听项就要重算候选
watch(() => [...form.identifiers], pruneValueIdentifier)
watch(() => form.emitMode, (mode) => {
  if (mode !== 'EVERY_MATCH') return
  form.levels.forEach(level => {
    level.cooldownMillis = undefined
  })
})

onMounted(async () => {
  try {
    const response = await getRuleCapabilities()
    if (response.data) Object.assign(capabilities, response.data)
  } catch (error: unknown) {
    console.error('获取规则运行时能力失败', error)
  }
  await Promise.all([loadRules(), loadRuleOutputs()])
})
</script>

<style scoped>
.rule-form :deep(.el-form-item) {
  margin-bottom: 10px;
}

.rule-tabs :deep(.el-tabs__header) {
  margin-bottom: 10px;
}

.rule-tabs :deep(.el-tabs__item) {
  height: 32px;
  padding: 0 14px;
  font-size: 12px;
}

.rule-tabs :deep(.el-tab-pane) {
  min-height: 410px;
}

.window-option {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 16px;
  min-width: 330px;
}

.window-option span:last-child {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.window-hint {
  margin: 0 0 10px;
  padding: 7px 10px;
  border-left: 3px solid var(--el-color-primary-light-5);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 11px;
  line-height: 1.55;
}

.window-hint-title {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.window-hint-example {
  color: var(--el-text-color-secondary);
}

.field-hint {
  padding-left: 92px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.6;
}

.duration-input {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 76px;
  width: 100%;
  gap: 6px;
}

.duration-input :deep(.el-input-number) {
  width: 100%;
}

.level-card {
  margin-bottom: 10px;
  padding: 8px 10px 0;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  background: var(--el-fill-color-blank);
}

.level-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.level-card-title {
  color: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 600;
}

.level-card-note {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

:global(.rule-edit-dialog .el-dialog__body) {
  max-height: min(68vh, 640px);
  overflow-y: auto;
}

.script-help {
  margin-top: 5px;
  padding: 7px 9px;
  border: 1px solid #e5e7eb;
  border-radius: 4px;
  background: #f8fafc;
  color: #64748b;
  font-size: 11px;
  line-height: 1.65;
}

.script-help code {
  padding: 1px 3px;
  border-radius: 3px;
  background: #eaf0f7;
  color: #334155;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.script-help-title {
  margin-right: 5px;
  color: #334155;
  font-weight: 600;
}
</style>
