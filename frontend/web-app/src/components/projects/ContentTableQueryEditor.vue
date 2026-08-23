<script setup lang="ts">
import {
  ContentSortDirection,
  ContentSortField,
  WorkItemPriority,
  type ContentViewSort,
} from '@yumpoo/api-client'
import {
  ElButton,
  ElDatePicker,
  ElInput,
  ElOption as ElOptionRaw,
  ElSelect as ElSelectRaw,
} from 'element-plus'
import { onBeforeUnmount, ref, watch, type DefineComponent } from 'vue'
import { cloneTableQuery, type ContentTableQuery } from './contentTableQuery'

interface StatusOption { statusCode: string; displayName: string }
interface MemberOption { userId: string; displayName: string; membershipStatus?: string }

const props = withDefaults(defineProps<{
  modelValue: ContentTableQuery
  statuses: StatusOption[]
  members: MemberOption[]
  disabled?: boolean
}>(), { disabled: false })
const emit = defineEmits<{
  'update:modelValue': [value: ContentTableQuery]
  search: [value: ContentTableQuery]
  change: [value: ContentTableQuery]
}>()

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const keyword = ref(props.modelValue.filters.query ?? '')
let searchTimer: ReturnType<typeof setTimeout> | undefined

watch(() => props.modelValue.filters.query, value => { keyword.value = value ?? '' })
onBeforeUnmount(() => { if (searchTimer) clearTimeout(searchTimer) })

function update(mutator: (draft: ContentTableQuery) => void, event: 'search' | 'change'): void {
  const draft = cloneTableQuery(props.modelValue)
  mutator(draft)
  emit('update:modelValue', draft)
  if (event === 'search') emit('search', draft)
  else emit('change', draft)
}

function updateKeyword(value: string): void {
  keyword.value = value
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => update(draft => {
    draft.filters.query = value.trim() || null
  }, 'search'), 300)
}

function setValues(field: 'statusCodes' | 'priorities' | 'assigneeUserIds', values: string[]): void {
  update(draft => {
    if (field === 'priorities') draft.filters.priorities = new Set(values as WorkItemPriority[])
    else draft.filters[field] = new Set(values)
  }, 'change')
}

function setDay(field: 'dueFrom' | 'dueTo', value: string | null): void {
  update(draft => { draft.filters[field] = value ? new Date(`${value}T00:00:00.000Z`) : null }, 'change')
}

function setUpdatedAfter(value: Date | null): void {
  update(draft => { draft.filters.updatedAfter = value }, 'change')
}

function addSort(): void {
  update(draft => {
    const used = new Set(draft.sort.map(item => item.field))
    const field = sortOptions.find(item => !used.has(item.value))?.value
    if (field) draft.sort.push({ field, direction: ContentSortDirection.Asc })
  }, 'change')
}

function updateSort(index: number, patch: Partial<ContentViewSort>): void {
  update(draft => {
    const current = draft.sort[index]
    if (current) draft.sort[index] = { ...current, ...patch }
  }, 'change')
}

function removeSort(index: number): void {
  update(draft => { draft.sort.splice(index, 1) }, 'change')
}

function day(value: Date | null): string | null {
  return value?.toISOString().slice(0, 10) ?? null
}

const priorities = [
  { value: WorkItemPriority.Low, label: '低' },
  { value: WorkItemPriority.Medium, label: '中' },
  { value: WorkItemPriority.High, label: '高' },
  { value: WorkItemPriority.Urgent, label: '紧急' },
]
const sortOptions = [
  { value: ContentSortField.ItemNo, label: '事项编号' },
  { value: ContentSortField.Title, label: '标题' },
  { value: ContentSortField.Status, label: '状态' },
  { value: ContentSortField.Priority, label: '优先级' },
  { value: ContentSortField.Assignee, label: '处理人' },
  { value: ContentSortField.Reporter, label: '报告人' },
  { value: ContentSortField.TimelineStartDate, label: '计划开始日' },
  { value: ContentSortField.TimelineEndDate, label: '计划结束日' },
  { value: ContentSortField.DueDate, label: '截止日' },
  { value: ContentSortField.UpdatedAt, label: '更新时间' },
]
</script>

<template>
  <div class="table-query-editor" aria-label="Table 高级查询">
    <el-input :model-value="keyword" clearable placeholder="搜索标题" aria-label="工作项标题关键字"
      :disabled="disabled" @update:model-value="updateKeyword" />
    <el-select :model-value="Array.from(modelValue.filters.statusCodes)" multiple clearable
      placeholder="状态" aria-label="工作项状态筛选" :disabled="disabled"
      @update:model-value="setValues('statusCodes', $event)">
      <el-option v-for="status in statuses" :key="status.statusCode"
        :label="status.displayName" :value="status.statusCode" />
    </el-select>
    <el-select :model-value="Array.from(modelValue.filters.priorities)" multiple clearable
      placeholder="优先级" aria-label="工作项优先级筛选" :disabled="disabled"
      @update:model-value="setValues('priorities', $event)">
      <el-option v-for="priority in priorities" :key="priority.value"
        :label="priority.label" :value="priority.value" />
    </el-select>
    <el-select :model-value="Array.from(modelValue.filters.assigneeUserIds)" multiple clearable filterable
      placeholder="处理人（含历史成员）" aria-label="工作项处理人筛选" :disabled="disabled"
      @update:model-value="setValues('assigneeUserIds', $event)">
      <el-option v-for="member in members" :key="member.userId"
        :label="`${member.displayName}${member.membershipStatus === 'REMOVED' ? '（历史）' : ''}`"
        :value="member.userId" />
    </el-select>
    <el-date-picker :model-value="day(modelValue.filters.dueFrom)" type="date" value-format="YYYY-MM-DD"
      placeholder="截止日起" aria-label="截止日起" :disabled="disabled"
      @update:model-value="setDay('dueFrom', $event)" />
    <el-date-picker :model-value="day(modelValue.filters.dueTo)" type="date" value-format="YYYY-MM-DD"
      placeholder="截止日止" aria-label="截止日止" :disabled="disabled"
      @update:model-value="setDay('dueTo', $event)" />
    <el-date-picker :model-value="modelValue.filters.updatedAfter" type="datetime"
      placeholder="更新时间晚于" aria-label="更新时间晚于" :disabled="disabled"
      @update:model-value="setUpdatedAfter" />
    <div class="query-sort-list">
      <div v-for="(sort, index) in modelValue.sort" :key="`${index}:${sort.field}`" class="query-sort-row">
        <span>{{ index + 1 }}</span>
        <el-select :model-value="sort.field" aria-label="排序字段" :disabled="disabled"
          @update:model-value="updateSort(index, { field: $event })">
          <el-option v-for="option in sortOptions" :key="option.value" :label="option.label"
            :value="option.value" :disabled="modelValue.sort.some((item, position) => position !== index && item.field === option.value)" />
        </el-select>
        <el-select :model-value="sort.direction" aria-label="排序方向" :disabled="disabled"
          @update:model-value="updateSort(index, { direction: $event })">
          <el-option label="升序" :value="ContentSortDirection.Asc" />
          <el-option label="降序" :value="ContentSortDirection.Desc" />
        </el-select>
        <el-button link type="danger" :disabled="disabled" @click="removeSort(index)">移除</el-button>
      </div>
      <el-button :disabled="disabled || modelValue.sort.length >= 3" @click="addSort">添加排序</el-button>
    </div>
  </div>
</template>

<style scoped>
.table-query-editor { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: var(--yp-space-3); padding: var(--yp-space-3); border: 1px solid var(--yp-border-subtle); background: var(--yp-bg-surface); }
.query-sort-list { grid-column: 1 / -1; display: grid; gap: var(--yp-space-2); }
.query-sort-row { display: grid; grid-template-columns: 24px minmax(160px, 1fr) minmax(100px, .5fr) auto; align-items: center; gap: var(--yp-space-2); }
@media (max-width: 900px) { .table-query-editor { grid-template-columns: 1fr 1fr; } }
@media (max-width: 560px) { .table-query-editor { grid-template-columns: 1fr; } .query-sort-row { grid-template-columns: 24px 1fr; } }
</style>
