<script setup lang="ts">
import { ElDialog, ElInput, ElCheckbox, ElTag, ElAlert, ElCheckboxGroup, ElButton } from 'element-plus'
import { computed, ref, watch } from 'vue'
import type { DashboardConnection, DashboardProject } from '@yumpoo/api-client'
import { dashboardsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
const props = defineProps<{ modelValue: boolean; selected: string[]; connections: DashboardConnection[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; apply: [ids: string[]] }>()
const draft = ref<string[]>([]), query = ref(''), archived = ref(false), items = ref<DashboardProject[]>([]), total = ref(0), loading = ref(false), error = ref('')
let sequence = 0, timer: ReturnType<typeof setTimeout>
const selectedRows = computed(() => draft.value.map(id => items.value.find(p => p.id === id) || props.connections.find(p => p.id === id)).filter(Boolean))
async function search(append = false) {
  const token = ++sequence; loading.value = true; error.value = ''
  try { const page = await dashboardsApi.listDashboardProjects({ query: query.value, includeArchived: archived.value, offset: append ? items.value.length : 0, limit: 100 }); if (token === sequence) { items.value = append ? [...items.value, ...page.items] : page.items; total.value = page.totalElements } }
  catch (reason) { if (token === sequence) error.value = problemMessage(await toApiProblem(reason)) }
  finally { if (token === sequence) loading.value = false }
}
watch(() => props.modelValue, open => { if (open) { draft.value = [...props.selected]; query.value = ''; void search() } else { ++sequence; clearTimeout(timer) } })
watch([query, archived], () => { clearTimeout(timer); timer = setTimeout(() => void search(), 200) })
</script>
<template>
  <el-dialog
    append-to-body
    :model-value="modelValue"
    title="连接项目"
    width="640px"
    class="dashboard-dialog"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <p class="dialog-intro">
      选择要汇总的项目。此设置只影响你的仪表板。
    </p>
    <el-input
      v-model="query"
      placeholder="搜索项目名称或编码"
      clearable
      aria-label="搜索连接项目"
    />
    <div class="project-selector-meta">
      <span>已选择 {{ draft.length }} / 100</span><el-checkbox v-model="archived">
        显示已归档项目
      </el-checkbox>
    </div>
    <div
      v-if="selectedRows.length"
      class="selected-projects"
    >
      <el-tag
        v-for="p in selectedRows"
        :key="p!.id"
        closable
        @close="draft = draft.filter(id => id !== p!.id)"
      >
        {{ p!.name || '无法访问的项目' }}
      </el-tag>
    </div>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
    />
    <el-checkbox-group
      v-model="draft"
      class="project-selector-list"
      :max="100"
    >
      <div
        v-for="p in items"
        :key="p.id"
        class="project-selector-row"
      >
        <el-checkbox :value="p.id">
          <span class="project-symbol">▦</span><span class="project-row-copy"><strong>{{ p.name }}</strong><small>{{ p.code }}<span v-if="p.lifecycle === 'ARCHIVED'"> · 已归档</span></small></span>
        </el-checkbox>
      </div>
      <div
        v-if="!items.length && !loading"
        class="dialog-empty"
      >
        {{ query ? '没有找到匹配项目' : '暂无可连接的项目，请先加入一个项目' }}
      </div>
    </el-checkbox-group>
    <el-button
      v-if="items.length < total"
      text
      :loading="loading"
      @click="search(true)"
    >
      加载更多项目
    </el-button>
    <div
      v-if="loading"
      class="dialog-empty"
    >
      正在加载项目…
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">
        取消
      </el-button><el-button
        type="primary"
        :disabled="loading"
        @click="emit('apply', draft); emit('update:modelValue', false)"
      >
        应用 · {{ draft.length }} 个项目
      </el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.project-selector-meta{display:flex;align-items:center;justify-content:space-between;margin:12px 0;color:var(--yp-text-secondary);font-size:12px}.selected-projects{display:flex;flex-wrap:wrap;gap:6px;margin-bottom:12px}.project-selector-list{display:block;max-height:350px;overflow:auto;border-top:1px solid var(--yp-border-default)}.project-selector-row{display:block;padding:10px 8px;border-bottom:1px solid var(--yp-border-default)}.project-selector-row:hover{background:var(--yp-bg-sunken)}.project-selector-row :deep(.el-checkbox){height:auto;width:100%}.project-selector-row :deep(.el-checkbox__label){display:flex;align-items:center;gap:12px;min-width:0;white-space:normal}.project-row-copy{display:flex;flex-direction:column;gap:3px}.project-row-copy strong{font-weight:500}.project-row-copy small{color:var(--yp-text-secondary)}.project-symbol{font-size:25px;color:var(--yp-action-primary)}
</style>
