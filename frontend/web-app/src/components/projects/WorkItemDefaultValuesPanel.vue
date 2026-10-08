<script setup lang="ts">
import './workItemTableSettings.css'
import type { WorkItemCreateDefaultValues } from '@yumpoo/api-client'
import { ElInputNumber, ElOption as ElOptionRaw, ElSelect as ElSelectRaw } from 'element-plus'
import { computed, type DefineComponent } from 'vue'
import type { DefaultValueColumnKey, TableSettingsCatalog } from './workItemTableSettings'

type DueMode = 'NONE' | 'TODAY' | 'AFTER'
const props = defineProps<{
  values: WorkItemCreateDefaultValues
  catalog: TableSettingsCatalog
  popperClass?: string
  /** 按当前列顺序排列的可设置默认值的列。 */
  columns: Array<{ key: DefaultValueColumnKey; label: string }>
}>()
const emit = defineEmits<{ change: [values: WorkItemCreateDefaultValues]; back: [] }>()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const DUE_MODES: Array<{ value: DueMode; label: string }> = [
  { value: 'NONE', label: '不设置' },
  { value: 'TODAY', label: '创建当天' },
  { value: 'AFTER', label: '创建后若干天' },
]

const statuses = computed(() => props.catalog.statuses.filter(item => item.active))
const priorities = computed(() => props.catalog.priorities.filter(item => item.active))
const contents = computed(() => props.catalog.contents.filter(item => item.active))
const assigneeIds = computed(() => [...props.values.assigneeUserIds].filter(id => props.catalog.members.some(item => item.userId === id)))
const statusCode = computed(() => statuses.value.find(item => item.code === props.values.statusCode)?.code)
const priority = computed(() => priorities.value.find(item => item.code === props.values.priority)?.code)
const contentId = computed(() => contents.value.find(item => item.id === props.values.contentId)?.id)
const dueMode = computed<DueMode>(() => props.values.dueDateOffsetDays === null ? 'NONE'
  : props.values.dueDateOffsetDays === 0 ? 'TODAY' : 'AFTER')
const configured = computed(() => props.values.assigneeUserIds.size > 0 || props.values.statusCode !== null
  || props.values.priority !== null || props.values.contentId !== null || props.values.dueDateOffsetDays !== null)

function change(patch: Partial<WorkItemCreateDefaultValues>): void {
  emit('change', { ...props.values, ...patch })
}

function setDueMode(mode: DueMode): void {
  change({ dueDateOffsetDays: mode === 'NONE' ? null : mode === 'TODAY' ? 0 : Math.max(1, props.values.dueDateOffsetDays ?? 1) })
}

function clearAll(): void {
  emit('change', { assigneeUserIds: new Set<string>(), statusCode: null, priority: null, contentId: null, dueDateOffsetDays: null })
}
</script>

<template>
  <section class="table-settings-panel" aria-label="新建默认值">
    <header class="table-settings-panel__header">
      <button type="button" class="table-settings-back" aria-label="返回表格设置" @click="emit('back')">‹</button>
      <strong>新建默认值</strong>
      <button type="button" class="table-settings-link table-settings-panel__action" :disabled="!configured" @click="clearAll">清除全部</button>
    </header>
    <p class="table-settings-panel__hint">仅对你在本项目新建的工作项生效：表格底部添加、在下方插入、分组末尾添加。分组添加时以所在分组的值为准；子项与复制不使用默认值。</p>
    <div class="table-settings-defaults">
      <template v-for="column in columns" :key="column.key">
        <span class="table-settings-defaults__label">{{ column.label }}</span>
        <el-select
          v-if="column.key === 'assignee'"
          :model-value="assigneeIds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          filterable
          clearable
          :multiple-limit="20"
          placeholder="不设置"
          :aria-label="`${column.label}默认值`"
          :popper-class="`work-item-view-control ${popperClass ?? ''}`"
          @update:model-value="change({ assigneeUserIds: new Set<string>($event) })"
        >
          <el-option v-for="member in catalog.members" :key="member.userId" :value="member.userId" :label="member.displayName" />
        </el-select>
        <el-select
          v-else-if="column.key === 'status'"
          :model-value="statusCode"
          clearable
          placeholder="不设置（初始状态）"
          :aria-label="`${column.label}默认值`"
          :popper-class="`work-item-view-control ${popperClass ?? ''}`"
          @update:model-value="change({ statusCode: $event || null })"
        >
          <el-option v-for="item in statuses" :key="item.code" :value="item.code" :label="item.displayName" />
        </el-select>
        <el-select
          v-else-if="column.key === 'priority'"
          :model-value="priority"
          clearable
          placeholder="不设置"
          :aria-label="`${column.label}默认值`"
          :popper-class="`work-item-view-control ${popperClass ?? ''}`"
          @update:model-value="change({ priority: $event || null })"
        >
          <el-option v-for="item in priorities" :key="item.code" :value="item.code" :label="item.displayName" />
        </el-select>
        <el-select
          v-else-if="column.key === 'content'"
          :model-value="contentId"
          clearable
          placeholder="不设置（第一个启用的类别）"
          :aria-label="`${column.label}默认值`"
          :popper-class="`work-item-view-control ${popperClass ?? ''}`"
          @update:model-value="change({ contentId: $event || null })"
        >
          <el-option v-for="item in contents" :key="item.id" :value="item.id" :label="item.name" />
        </el-select>
        <div v-else class="table-settings-due">
          <el-select
            :model-value="dueMode"
            :aria-label="`${column.label}默认值`"
            :popper-class="`work-item-view-control ${popperClass ?? ''}`"
            @update:model-value="setDueMode($event)"
          >
            <el-option v-for="mode in DUE_MODES" :key="mode.value" :value="mode.value" :label="mode.label" />
          </el-select>
          <template v-if="dueMode === 'AFTER'">
            <el-input-number
              :model-value="values.dueDateOffsetDays ?? 1"
              :min="1"
              :max="365"
              step-strictly
              controls-position="right"
              aria-label="创建后天数"
              @update:model-value="change({ dueDateOffsetDays: $event ?? 1 })"
            />
            <span>天</span>
          </template>
        </div>
      </template>
    </div>
  </section>
</template>
