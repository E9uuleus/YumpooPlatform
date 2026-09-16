<script setup lang="ts">
import { ElDialog, ElInput, ElButton, ElCheckboxGroup, ElCheckbox, ElDatePicker } from 'element-plus'
import { computed, ref, watch } from 'vue'
import type { DashboardBucket, DashboardConnection, DashboardFilters } from '@yumpoo/api-client'
import { clone, emptyFilters, categories } from './dashboardModel'
import YpAssignee from '../yp/YpAssignee.vue'
const props = defineProps<{ modelValue: boolean; filters: DashboardFilters; options: DashboardBucket[]; projects: DashboardConnection[]; initialField?: string; userId?: string | undefined }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; apply: [value: DashboardFilters] }>()
const draft = ref(emptyFilters()), active = ref('assignees'), query = ref('')
const fields = [{ key: 'assignees', title: '处理人', kind: 'MEMBER' }, { key: 'projectIds', title: '项目', kind: 'PROJECT' }, { key: 'statuses', title: '状态', kind: 'STATUS' }, { key: 'categories', title: '状态分类', kind: 'CATEGORY' }, { key: 'priorities', title: '优先级', kind: 'PRIORITY' }, { key: 'contentIds', title: '工作项类型', kind: 'CONTENT' }, { key: 'due', title: '截止日期', kind: '' }, { key: 'other', title: '其他', kind: '' }]
const field = computed(() => fields.find(f => f.key === active.value)!)
type ListKey = 'assignees' | 'projectIds' | 'statuses' | 'categories' | 'priorities' | 'contentIds'
const choices = computed(() => {
  const rows: { key: string; label: string; detail: string; userId: string | null; count: number | undefined }[] = field.value.kind === 'PROJECT' ? props.projects.filter(p => p.available).map(p => ({ key: p.id, label: p.name || '', detail: p.code || '', userId: null, count: undefined }))
    : field.value.kind === 'CATEGORY' ? Object.entries(categories).map(([key, c]) => ({ key, label: c.name, detail: '', userId: null, count: undefined }))
      : props.options.filter(b => b.kind === field.value.kind).map(b => ({ key: b.kind === 'CONTENT' ? b.code || b.key : b.key, label: b.label || b.code || '', detail: b.projectId ? props.projects.find(p => p.id === b.projectId)?.name || '' : '', userId: b.userId, count: b.count }))
  if (active.value === 'assignees' && !rows.some(r => r.key === 'UNASSIGNED')) rows.unshift({ key: 'UNASSIGNED', label: '未分配', detail: '', userId: null, count: 0 })
  return rows.filter(r => `${r.label} ${r.detail}`.toLowerCase().includes(query.value.toLowerCase()))
})
const values = computed({ get: () => draft.value[active.value as ListKey] || [], set: (value: string[]) => { draft.value[active.value as ListKey] = value } })
function count(key: string) { const value = draft.value[key as ListKey]; return Array.isArray(value) ? value.length : 0 }
function dateValue(field: 'dueFrom' | 'dueTo', value: string | null) { draft.value[field] = value ? new Date(`${value}T00:00:00Z`) : null }
watch(() => props.modelValue, open => { if (open) { draft.value = clone(props.filters); active.value = props.initialField || 'assignees'; query.value = '' } })
watch(active, () => { query.value = '' })
</script>
<template>
  <el-dialog
    append-to-body
    :model-value="modelValue"
    title="筛选仪表板"
    width="640px"
    class="dashboard-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="dialog-intro">
      所有图表应用相同筛选。同一字段多选取并集，不同字段之间取交集。
    </p>
    <div class="dashboard-filter-body">
      <nav aria-label="筛选字段">
        <button
          v-for="f in fields"
          :key="f.key"
          :class="{ active: active === f.key }"
          @click="active = f.key"
        >
          {{ f.title }}<span v-if="count(f.key)">{{ count(f.key) }}</span>
        </button>
      </nav>
      <div class="dashboard-filter-options">
        <template v-if="field.kind">
          <el-input
            v-model="query"
            :placeholder="`搜索${field.title}`"
            clearable
          />
          <el-button
            v-if="active === 'assignees' && userId"
            text
            type="primary"
            @click="values = [userId]"
          >
            只看分配给我
          </el-button>
          <el-checkbox-group v-model="values">
            <el-checkbox
              v-for="choice in choices"
              :key="choice.key"
              :value="choice.key"
            >
              <span class="filter-choice"><YpAssignee
                v-if="active === 'assignees'"
                :user-id="choice.userId"
                :display-name="choice.label"
                size="table"
              /><span v-else>{{ choice.label }}<small v-if="choice.detail">{{ choice.detail }}</small></span><small v-if="choice.count !== undefined">{{ choice.count }}</small></span>
            </el-checkbox>
          </el-checkbox-group>
          <div
            v-if="!choices.length"
            class="dialog-empty"
          >
            暂无选项
          </div>
        </template>
        <template v-else-if="active === 'due'">
          <label class="dashboard-form-label">开始日期</label><el-date-picker
            :model-value="draft.dueFrom"
            type="date"
            placeholder="不限"
            @update:model-value="draft.dueFrom = $event; dateValue('dueFrom', $event ? new Date($event).toLocaleDateString('sv-SE') : null)"
          /><label class="dashboard-form-label">结束日期</label><el-date-picker
            :model-value="draft.dueTo"
            type="date"
            placeholder="不限"
            @update:model-value="draft.dueTo = $event; dateValue('dueTo', $event ? new Date($event).toLocaleDateString('sv-SE') : null)"
          /><p class="dialog-intro">
            按工作项截止日期筛选；耗时仍汇总这些工作项的全部实际计时。
          </p>
        </template>
        <template v-else>
          <el-checkbox v-model="draft.includeArchived">
            包含已归档工作项
          </el-checkbox><el-checkbox v-model="draft.hasTime">
            仅显示有计时记录的工作项
          </el-checkbox><p class="dialog-intro">
            已删除的工作项和计时记录始终排除。
          </p>
        </template>
      </div>
    </div>
    <template #footer>
      <el-button
        text
        class="filter-reset"
        @click="draft = emptyFilters()"
      >
        清除全部
      </el-button><el-button @click="emit('update:modelValue', false)">
        取消
      </el-button><el-button
        type="primary"
        :disabled="!!(draft.dueFrom && draft.dueTo && new Date(draft.dueFrom) > new Date(draft.dueTo))"
        @click="emit('apply', draft); emit('update:modelValue', false)"
      >
        应用筛选
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.dashboard-filter-body{display:grid;grid-template-columns:130px minmax(0,1fr);min-height:350px;border-top:1px solid var(--yp-border-default);margin-top:16px}.dashboard-filter-body nav{border-right:1px solid var(--yp-border-default);padding:12px 12px 0 0}.dashboard-filter-body nav button{width:100%;border:0;background:transparent;text-align:left;padding:10px;border-radius:4px;color:var(--yp-text-primary);cursor:pointer;display:flex;justify-content:space-between}.dashboard-filter-body nav button.active{background:var(--yp-bg-selected);color:var(--yp-action-primary)}.dashboard-filter-options{padding:16px;overflow:auto;max-height:430px}.dashboard-filter-options :deep(.el-checkbox-group){display:flex;flex-direction:column;margin-top:12px}.dashboard-filter-options :deep(.el-checkbox){width:100%;height:auto;min-height:42px;margin:0}.dashboard-filter-options :deep(.el-checkbox__label){width:100%;white-space:normal}.filter-choice{display:flex;align-items:center;justify-content:space-between;gap:12px}.filter-choice small{display:block;color:var(--yp-text-secondary);font-size:11px}.filter-reset{float:left}@media(max-width:500px){.dashboard-filter-body{grid-template-columns:100px minmax(0,1fr)}.dashboard-filter-options{padding:12px 8px}}
</style>
