<script lang="ts">
import { ProjectLifecycleFilter, type ProjectSummary } from '@yumpoo/api-client'
import { projectsApi } from '../../api/client'
import { MY_PROJECT_ACCESSES } from '../projects/projectScope'

// The last project used stays the next default; every newly opened row reads the current writable projects.
let lastProjectId: string | null = null
function loadProjects(): Promise<ProjectSummary[]> {
  return projectsApi.listProjects({ lifecycle: ProjectLifecycleFilter.Active, ended: false, actorAccesses: MY_PROJECT_ACCESSES, page: 0, size: 100 })
    .then(page => page.items)
}
</script>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, type DefineComponent } from 'vue'
import { readCsrfToken, type WorkItemDetail } from '@yumpoo/api-client'
import { ElInput, ElOption as ElOptionRaw, ElSelect as ElSelectRaw } from 'element-plus'
import { contentsApi, workItemsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'
import { projectColors } from '../team/teamDashboardModel'
import TimerIcon from '../timer/TimerIcon.vue'
import { itemStatus, type QuickItemStatus } from './quickTime'

const ElSelect = ElSelectRaw as unknown as DefineComponent
const ElOption = ElOptionRaw as unknown as DefineComponent
const props = defineProps<{
  /** Default project candidates in order (the active chip, the panel's context project); unknown or empty ones are skipped. */
  preferred: Array<string | null>
  initialTitle: string
  userId: string
  tones: Map<string, string>
}>()
const emit = defineEmits<{ close: []; created: [detail: WorkItemDetail, projectName: string, status: QuickItemStatus]; failed: [message: string] }>()
const projects = ref<ProjectSummary[]>([])
const projectId = ref<string>()
const title = ref(props.initialTitle)
const loading = ref(true)
const saving = ref(false)
const input = ref<InstanceType<typeof ElInput>>()
let attempt: { signature: string; key: string } | undefined

const tone = computed(() => projectId.value ? props.tones.get(projectId.value) ?? projectColors([projectId.value]).get(projectId.value) : undefined)

onMounted(async () => {
  void nextTick(() => input.value?.focus())
  try {
    projects.value = await loadProjects()
    projectId.value = [...props.preferred, lastProjectId, projects.value[0]?.id]
      .find(id => id && projects.value.some(project => project.id === id)) ?? undefined
  } catch (reason) {
    emit('failed', problemMessage(await toApiProblem(reason)))
  } finally { loading.value = false }
})

async function create() {
  const name = title.value.trim(), project = projects.value.find(item => item.id === projectId.value)
  if (!name || !project || saving.value) return
  const csrf = readCsrfToken()
  if (!csrf) { emit('failed', '缺少 CSRF 凭据，请刷新后重试。'); return }
  saving.value = true
  try {
    // The quick panel files the item in the project's first category and leaves the personal table defaults alone.
    const [catalog, labels] = await Promise.all([
      contentsApi.listProjectContents({ projectId: project.id }), workItemsApi.getProjectWorkItemLabels({ projectId: project.id }),
    ])
    const content = catalog.items
      .filter(item => item.active).sort((left, right) => left.sortOrder - right.sortOrder)[0]
    if (!content) { emit('failed', '该项目没有可用的类别'); return }
    const signature = JSON.stringify([project.id, content.id, name])
    if (attempt?.signature !== signature) attempt = { signature, key: crypto.randomUUID() }
    const detail = await workItemsApi.createWorkItem({ projectId: project.id, xXSRFTOKEN: csrf, idempotencyKey: attempt.key, workItemCreateRequest: {
      contentId: content.id, title: name, priority: null, description: null, notes: null,
      assigneeUserIds: [props.userId], assigneeUserId: props.userId, timelineStartDate: null, timelineEndDate: null, dueDate: null } })
    attempt = undefined
    lastProjectId = project.id
    emit('created', detail, project.name, itemStatus(detail, labels))
  } catch (reason) {
    emit('failed', problemMessage(await toApiProblem(reason)))
  } finally {
    saving.value = false
    void nextTick(() => input.value?.focus())
  }
}

function onTitleKeydown(event: Event | KeyboardEvent) {
  if (!(event instanceof KeyboardEvent)) return
  if (event.isComposing || event.keyCode === 229 || event.key !== 'Enter') return
  event.preventDefault()
  void create()
}
</script>

<template>
  <div
    class="quick-create"
    role="group"
    aria-label="新建工作项"
  >
    <el-select
      v-model="projectId"
      class="quick-create__project"
      size="small"
      teleported
      :loading="loading"
      :disabled="saving"
      placeholder="项目"
      aria-label="项目"
      popper-class="quick-create__projects"
    >
      <template #prefix>
        <span
          class="quick-create__dot"
          :style="{ background: tone }"
        />
      </template>
      <el-option
        v-for="project in projects"
        :key="project.id"
        :label="project.name"
        :value="project.id"
      />
    </el-select>
    <el-input
      ref="input"
      v-model="title"
      class="quick-create__title"
      size="small"
      placeholder="输入标题，Enter 创建…"
      aria-label="新工作项标题"
      :disabled="saving"
      @keydown="onTitleKeydown"
    />
    <button
      type="button"
      class="quick-create__close"
      aria-label="取消新建"
      title="取消新建（Esc）"
      @click="emit('close')"
    >
      <TimerIcon name="close" />
    </button>
  </div>
</template>

<style scoped>
.quick-create{display:flex;align-items:center;gap:6px;height:36px;margin:2px 0 4px;padding:0 4px 0 6px;border-radius:9px;background:var(--yp-timer-sunken)}
.quick-create__project{width:112px;flex-shrink:0}
.quick-create__project :deep(.el-select__wrapper){min-height:26px;padding:0 8px;background:var(--yp-bg-raised);box-shadow:0 0 0 1px var(--yp-timer-hairline) inset}
.quick-create__dot{display:block;width:7px;height:7px;border-radius:50%;background:var(--yp-text-muted)}
.quick-create__title{flex:1;min-width:0}
.quick-create__title :deep(.el-input__wrapper){min-height:26px;background:var(--yp-bg-raised)}
.quick-create__close{display:grid;place-items:center;width:24px;height:24px;flex-shrink:0;padding:0;border:0;border-radius:6px;background:none;color:var(--yp-text-secondary);cursor:pointer}
.quick-create__close:hover{background:var(--yp-timer-hover);color:var(--yp-text-primary)}
.quick-create__close svg{width:14px;height:14px}
</style>
