<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElInput } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { ProjectMembershipStatusFilter, type ProjectMember, type WorkItemAssignee } from '@yumpoo/api-client'
import { projectsApi } from '../../api/client'
import YpAssignee from '../yp/YpAssignee.vue'
import { MAX_WORK_ITEM_ASSIGNEES } from './workItemAssignees'

/** 每次增删立即通过 change 提交完整处理人列表；列表顺序决定主处理人。 */
const props = defineProps<{ projectId: string; selected: readonly WorkItemAssignee[]; busy?: boolean | undefined }>()
const emit = defineEmits<{ change: [userIds: string[]] }>()
const query = ref(''), members = ref<ProjectMember[]>([]), loading = ref(false), failed = ref(false)
const input = ref<InstanceType<typeof ElInput>>()
const selectedIds = computed(() => props.selected.map(item => item.userId))
const candidates = computed(() => members.value.filter(member => !selectedIds.value.includes(member.userId)))
const full = computed(() => selectedIds.value.length >= MAX_WORK_ITEM_ASSIGNEES)
let timer: ReturnType<typeof setTimeout> | undefined, controller: AbortController | undefined
async function load() {
  controller?.abort(); controller = new AbortController()
  const signal = controller.signal
  loading.value = true; failed.value = false
  try {
    const result = await projectsApi.listProjectMembers({ projectId: props.projectId, status: ProjectMembershipStatusFilter.Active,
      ...(query.value.trim() ? { q: query.value.trim() } : {}), page: 0, size: 100 }, { signal })
    if (!signal.aborted) members.value = result.items
  } catch (reason) {
    if (!(reason instanceof DOMException && reason.name === 'AbortError')) failed.value = true
  } finally { if (!signal.aborted) loading.value = false }
}
function add(userId: string) { if (!props.busy && !full.value) emit('change', [...selectedIds.value, userId]) }
function remove(userId: string) { if (!props.busy) emit('change', selectedIds.value.filter(id => id !== userId)) }
watch(query, () => { clearTimeout(timer); timer = setTimeout(() => { void load() }, 250) })
watch(() => props.projectId, () => { void load() })
onMounted(() => { void load(); input.value?.focus() })
onBeforeUnmount(() => { clearTimeout(timer); controller?.abort() })
</script>

<template>
  <div class="assignee-picker">
    <div v-if="selected.length" class="assignee-picker__selected" aria-label="已选处理人">
      <span v-for="assignee in selected" :key="assignee.userId" class="assignee-picker__chip">
        <yp-assignee :user-id="assignee.userId" :display-name="assignee.displayName" size="table" tooltip-disabled />
        <button type="button" class="assignee-picker__remove" :disabled="busy" :aria-label="`移除处理人${assignee.displayName}`" @click="remove(assignee.userId)">×</button>
      </span>
    </div>
    <el-input ref="input" v-model="query" :prefix-icon="Search" clearable placeholder="搜索项目成员" aria-label="搜索项目成员" />
    <div class="assignee-picker__list" :aria-busy="loading">
      <p class="assignee-picker__caption">{{ full ? `最多 ${MAX_WORK_ITEM_ASSIGNEES} 位处理人` : '建议成员' }}</p>
      <button v-for="member in candidates" :key="member.userId" type="button" class="assignee-picker__option" :disabled="busy || full" @click="add(member.userId)">
        <yp-assignee :user-id="member.userId" :display-name="member.displayName" />
      </button>
      <button v-if="selected.length" type="button" class="assignee-picker__option" :disabled="busy" @click="emit('change', [])">
        <span class="assignee-picker__empty">—</span><span>清空处理人</span>
      </button>
      <p v-if="failed" class="assignee-picker__hint" role="alert">成员加载失败，请重试</p>
      <p v-else-if="!loading && !candidates.length" class="assignee-picker__hint" role="status">没有匹配的成员</p>
    </div>
  </div>
</template>

<style scoped>
.assignee-picker { display: grid; gap: 8px; }
.assignee-picker__selected { display: flex; flex-wrap: wrap; gap: 6px; }
.assignee-picker__chip { display: inline-flex; max-width: 100%; align-items: center; gap: 2px; padding: 2px 4px 2px 2px; border-radius: var(--yp-radius-pill); background: var(--yp-bg-sunken); }
.assignee-picker__remove { display: inline-grid; width: 18px; height: 18px; place-items: center; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--yp-text-secondary); cursor: pointer; }
.assignee-picker__remove:hover:not(:disabled) { background: var(--yp-bg-hover); color: var(--yp-text-primary); }
.assignee-picker__list { display: grid; max-height: 260px; overflow-y: auto; }
.assignee-picker__caption { margin: 4px 8px; color: var(--yp-text-muted); font-size: 12px; }
.assignee-picker__option { display: flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 8px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-primary); font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.assignee-picker__option:hover:not(:disabled) { background: var(--yp-bg-hover); }
.assignee-picker__option:disabled { cursor: not-allowed; opacity: .55; }
.assignee-picker__empty { display: inline-grid; width: 24px; height: 24px; place-items: center; border: 1px dashed var(--yp-border-strong); border-radius: 50%; color: var(--yp-text-muted); }
.assignee-picker__hint { margin: 8px; color: var(--yp-text-muted); font-size: 12px; text-align: center; }
</style>
