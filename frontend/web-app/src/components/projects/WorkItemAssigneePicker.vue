<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElInput } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { ProjectMembershipStatusFilter, type ProjectMember } from '@yumpoo/api-client'
import { projectsApi } from '../../api/client'
import YpAssignee from '../yp/YpAssignee.vue'

/** Active members of one project; used where the table's preloaded member list is not available. */
const props = defineProps<{ projectId: string; currentUserId?: string | null | undefined }>()
const emit = defineEmits<{ select: [userId: string | null] }>()
const query = ref(''), members = ref<ProjectMember[]>([]), loading = ref(false), failed = ref(false)
const input = ref<InstanceType<typeof ElInput>>()
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
watch(query, () => { clearTimeout(timer); timer = setTimeout(() => { void load() }, 250) })
watch(() => props.projectId, () => { void load() })
onMounted(() => { void load(); input.value?.focus() })
onBeforeUnmount(() => { clearTimeout(timer); controller?.abort() })
</script>

<template>
  <div class="assignee-picker">
    <el-input
      ref="input"
      v-model="query"
      :prefix-icon="Search"
      clearable
      placeholder="搜索项目成员"
      aria-label="搜索项目成员"
    />
    <div
      class="assignee-picker__list"
      :aria-busy="loading"
    >
      <button
        type="button"
        class="assignee-picker__option"
        :class="{ 'is-current': !currentUserId }"
        @click="emit('select', null)"
      >
        <span class="assignee-picker__empty">—</span><span>未分配</span>
      </button>
      <button
        v-for="member in members"
        :key="member.userId"
        type="button"
        class="assignee-picker__option"
        :class="{ 'is-current': member.userId === currentUserId }"
        @click="emit('select', member.userId)"
      >
        <yp-assignee
          :user-id="member.userId"
          :display-name="member.displayName"
        />
      </button>
      <p
        v-if="failed"
        class="assignee-picker__hint"
        role="alert"
      >
        成员加载失败，请重试
      </p>
      <p
        v-else-if="!loading && !members.length"
        class="assignee-picker__hint"
        role="status"
      >
        没有匹配的成员
      </p>
    </div>
  </div>
</template>

<style scoped>
.assignee-picker { display: grid; gap: 8px; }
.assignee-picker__list { display: grid; max-height: 260px; overflow-y: auto; }
.assignee-picker__option { display: flex; align-items: center; gap: 8px; min-height: 38px; padding: 0 8px; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-primary); font: inherit; font-size: 13px; text-align: left; cursor: pointer; }
.assignee-picker__option:hover { background: var(--yp-bg-hover); }
.assignee-picker__option.is-current { background: var(--yp-bg-selected); }
.assignee-picker__empty { display: inline-grid; width: 24px; height: 24px; place-items: center; border: 1px dashed var(--yp-border-strong); border-radius: 50%; color: var(--yp-text-muted); }
.assignee-picker__hint { margin: 8px; color: var(--yp-text-muted); font-size: 12px; text-align: center; }
</style>
