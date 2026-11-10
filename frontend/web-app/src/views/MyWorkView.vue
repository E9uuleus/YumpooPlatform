<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ListTimerCandidatesScopeEnum, type ProjectWorkItemListItem } from '@yumpoo/api-client'
import { ElButton, ElSkeleton, ElTooltip } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { timeTrackingApi } from '../api/client'
import { toApiProblem, type ApiProblem } from '../api/problems'
import InlineProblem from '../components/InlineProblem.vue'
import MyWorkItemDialog from '../components/my-work/MyWorkItemDialog.vue'
import MyWorkColumnMenu from '../components/my-work/MyWorkColumnMenu.vue'
import { useWorkItemPagePresentation } from '../components/projects/workItemPagePresentation'
import MyWorkViewMenu from '../components/my-work/MyWorkViewMenu.vue'
import { myWorkTableSource } from '../components/my-work/myWorkTableSource'
import { DEFAULT_MY_WORK_VIEW, isMyWorkViewField, type MyWorkViewField } from '../components/my-work/myWorkViews'
import ProjectWorkItems from '../components/projects/ProjectWorkItems.vue'
import WorkItemGroupToggle from '../components/projects/WorkItemGroupToggle.vue'
import { assigneeGroupColor } from '../components/projects/workItemGrouping'
import type { WorkItemTableSource } from '../components/projects/workItemTableSource'
import YpEmptyState from '../components/yp/YpEmptyState.vue'
import { orderByRecents, useProjectRecents } from '../composables/useProjectRecents'
import { useSession } from '../composables/useSession'
import { usePageOnboarding } from '../onboarding/useOnboarding'

type DetailTab = 'details' | 'discussion' | 'activity'
interface MyWorkProject { id: string; name: string; count: number }

const route = useRoute()
const router = useRouter()
const session = useSession()
const scope = computed(() => {
  const identity = session.authentication.value
  return identity ? `${identity.company.id}:${identity.user.id}` : 'unknown'
})
const presentation = useWorkItemPagePresentation(() => scope.value)
const { scrollbar, extent, overflow } = presentation
const recents = useProjectRecents(() => scope.value)
const projects = ref<MyWorkProject[]>([])
const orderedProjects = computed(() => orderByRecents(projects.value, recents.items.value))
const total = computed(() => projects.value.reduce((sum, project) => sum + project.count, 0))
const loading = ref(true)
const problem = ref<ApiProblem>()
const refreshKeys = ref<Record<string, number>>({})
const tables = ref<InstanceType<typeof ProjectWorkItems>[]>([])
const manualRefresh = ref(0)
const sources = new Map<string, WorkItemTableSource>()
/** 项目颜色与表格分组同一套调色板，同一会话内保持稳定且相邻项目不重色。 */
const accentTokens: Record<string, string> = {}
const dialog = ref<InstanceType<typeof MyWorkItemDialog>>()
const detailTab = ref<DetailTab>('details')
const detailId = computed(() => typeof route.query.workItemId === 'string' ? route.query.workItemId : undefined)
let sequence = 0

/** 按需加载：分区首次进入正文滚动区附近后才挂载表格；挂载后保留。 */
const body = ref<HTMLElement>()
const nearby = ref(new Set<string>())
let sectionObserver: IntersectionObserver | undefined
function observeSection(projectId: string, element: unknown): void {
  if (!(element instanceof HTMLElement) || nearby.value.has(projectId)) return
  element.dataset.projectId = projectId
  sectionObserver?.observe(element)
}

const collapsedKey = computed(() => `yumpoo:my-work:collapsed:v1:${scope.value}`)
const collapsed = ref(new Set<string>())
watch(collapsedKey, key => {
  try {
    const saved = JSON.parse(localStorage.getItem(key) ?? '[]') as unknown
    collapsed.value = new Set(Array.isArray(saved) ? saved.filter((id): id is string => typeof id === 'string') : [])
  } catch { collapsed.value = new Set() }
}, { immediate: true })
function toggle(projectId: string): void {
  const next = new Set(collapsed.value)
  if (!next.delete(projectId)) next.add(projectId)
  collapsed.value = next
  try { localStorage.setItem(collapsedKey.value, JSON.stringify([...next])) } catch { /* 本地存储不可用时只在当前页面保留折叠。 */ }
}

const viewKey = computed(() => `yumpoo:my-work:view:v1:${scope.value}`)
const view = ref<MyWorkViewField>(DEFAULT_MY_WORK_VIEW)
watch(viewKey, key => {
  let saved: unknown
  try { saved = localStorage.getItem(key) } catch { /* 本地存储不可用时使用默认视图。 */ }
  view.value = isMyWorkViewField(saved) ? saved : DEFAULT_MY_WORK_VIEW
}, { immediate: true })
function changeView(field: MyWorkViewField): void {
  view.value = field
  try { localStorage.setItem(viewKey.value, field) } catch { /* 本地存储不可用时只在当前页面保留视图。 */ }
}

const onboardingReady = computed(() => !loading.value && !problem.value)
const onboarding = usePageOnboarding(() => onboardingReady.value)
watch(onboardingReady, ready => { if (ready) void onboarding.ready() }, { flush: 'post' })

/** 项目与计数来自计时候选：分配给我、按本人完成定义未完成、我可写的项目。 */
async function loadProjects(): Promise<void> {
  const current = ++sequence
  try {
    const page = await timeTrackingApi.listTimerCandidates({
      q: '', scope: ListTimerCandidatesScopeEnum.Personal, offset: 0, limit: 1, assignedToMe: true, excludeCompleted: true,
    })
    if (current !== sequence) return
    projects.value = (page.projects ?? []).map(item => ({ id: item.projectId, name: item.projectName, count: item.count }))
    problem.value = undefined
  } catch (reason) {
    const failure = await toApiProblem(reason)
    if (current === sequence) problem.value = failure
  } finally {
    if (current === sequence) loading.value = false
  }
}
function refreshProject(projectId: string): void {
  refreshKeys.value = { ...refreshKeys.value, [projectId]: (refreshKeys.value[projectId] ?? 0) + 1 }
}
async function refresh(): Promise<void> {
  for (const table of tables.value) if (!await table.canClose()) return
  loading.value = true
  manualRefresh.value++
  await loadProjects()
}
function tableSource(projectId: string): WorkItemTableSource {
  if (!sources.has(projectId)) sources.set(projectId, myWorkTableSource(session.authentication.value?.user.id ?? ''))
  return sources.get(projectId)!
}
function preferenceScope(projectId: string): string {
  return `yumpoo:my-work-table:v1:${scope.value}:${projectId}`
}

function openDetail(item: ProjectWorkItemListItem, tab: DetailTab): void {
  detailTab.value = tab
  if (detailId.value !== item.id) void router.push({ query: { ...route.query, workItemId: item.id } })
}
function closeDetail(): void {
  const query = { ...route.query }
  delete query.workItemId
  void router.push({ query })
}
function onDetailChanged(projectId: string): void {
  refreshProject(projectId)
  void loadProjects()
}

// 嵌入的表格各自守卫未完成的编辑；这里只守卫详情弹窗中的草稿。
onBeforeRouteUpdate((to, from) => to.query.workItemId !== from.query.workItemId ? dialog.value?.beforeLeave() ?? true : true)
onBeforeRouteLeave(() => dialog.value?.beforeLeave() ?? true)
onMounted(() => {
  sectionObserver = new IntersectionObserver(entries => {
    const next = new Set(nearby.value)
    for (const entry of entries) {
      const projectId = (entry.target as HTMLElement).dataset.projectId
      if (!entry.isIntersecting || !projectId) continue
      next.add(projectId)
      sectionObserver?.unobserve(entry.target)
    }
    nearby.value = next
  }, { root: body.value ?? null, rootMargin: '400px 0px' })
  void loadProjects()
})
onBeforeUnmount(() => sectionObserver?.disconnect())
</script>

<template>
  <section class="dashboard-page my-work-page">
    <header class="dashboard-header">
      <div
        class="my-work-title"
        data-tour="my-work-projects"
      >
        <h1>我的工作</h1>
        <span v-if="!loading && projects.length">{{ total }} 项未完成 · {{ projects.length }} 个项目</span>
      </div>
      <div class="dashboard-header-actions">
        <my-work-column-menu :presentation="presentation" />
        <my-work-view-menu
          :model-value="view"
          @update:model-value="changeView"
        />
        <el-tooltip content="刷新">
          <el-button
            :icon="Refresh"
            aria-label="刷新我的工作"
            data-tour="my-work-refresh"
            :loading="loading"
            text
            @click="refresh"
          />
        </el-tooltip>
      </div>
    </header>
    <div
      ref="body"
      class="dashboard-canvas my-work-body"
    >
      <inline-problem
        v-if="problem"
        :problem="problem"
        title="我的工作加载失败"
      />
      <el-skeleton
        v-else-if="loading && !projects.length"
        :rows="8"
        animated
      />
      <yp-empty-state
        v-else-if="!projects.length"
        reason="empty"
        title="没有未完成的工作项"
        description="分配给你的工作项都已完成，新的指派会出现在这里。"
        ambient
      />
      <transition-group
        v-if="!problem && (!loading || projects.length)"
        name="my-work-project"
        tag="div"
        class="my-work-projects"
      >
        <section
          v-for="project in orderedProjects"
          :key="project.id"
          :ref="element => observeSection(project.id, element)"
          class="my-work-project"
          :class="{ 'my-work-project--collapsed': collapsed.has(project.id) }"
          :style="{ '--work-item-group-accent': assigneeGroupColor(project.id, accentTokens) }"
          :aria-label="`${project.name}中分配给我的工作项`"
        >
          <header class="my-work-project__heading">
            <work-item-group-toggle
              class="my-work-project__toggle"
              subject="项目"
              :label="project.name"
              :collapsed="collapsed.has(project.id)"
              @toggle="toggle(project.id)"
            >
              <small>{{ project.count }} 个工作项</small>
            </work-item-group-toggle>
            <router-link
              class="my-work-project__open"
              :to="{ name: 'project-overview', params: { projectId: project.id } }"
            >
              打开项目
            </router-link>
          </header>
          <div
            v-if="!collapsed.has(project.id)"
            class="my-work-project__table"
          >
            <project-work-items
              v-if="nearby.has(project.id)"
              :key="manualRefresh"
              ref="tables"
              :page-presentation="presentation"
              :embedded-project-id="project.id"
              :preference-scope="preferenceScope(project.id)"
              :source="tableSource(project.id)"
              :refresh-key="refreshKeys[project.id] ?? 0"
              :group-field="view"
              my-work
              @open-detail="openDetail"
              @changed="loadProjects"
            />
            <el-skeleton
              v-else
              class="my-work-project__placeholder"
              :rows="Math.min(project.count, 6)"
              animated
            />
          </div>
        </section>
      </transition-group>
    </div>
    <div ref="scrollbar" class="my-work-scrollbar" :class="{ 'my-work-scrollbar--visible': overflow }" tabindex="0"
      aria-label="我的工作横向滚动" @scroll.passive="presentation.onScroll">
      <div :style="{ width: `${extent}px`, height: '1px' }" />
    </div>
    <my-work-item-dialog
      ref="dialog"
      v-model:tab="detailTab"
      :work-item-id="detailId"
      @close="closeDetail"
      @changed="onDetailChanged"
    />
  </section>
</template>

<style src="../components/dashboard/dashboard.css" />
<style scoped>
.my-work-title { display: flex; min-width: 0; align-items: baseline; align-self: center; gap: var(--yp-space-3); }
.my-work-title h1 { margin: 0; font-size: var(--yp-type-card-title-size); font-weight: 600; white-space: nowrap; }
.my-work-title span { overflow: hidden; color: var(--yp-text-secondary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.my-work-scrollbar { flex: none; height: 0; overflow-x: auto; overflow-y: hidden; }
.my-work-scrollbar--visible { height: 14px; }
.my-work-body { overflow-x: hidden; background: var(--yp-bg-surface); }
.my-work-projects { display: grid; min-width: 0; gap: var(--yp-space-8); }
.my-work-project { min-width: 0; }
.my-work-project__heading {
  display: flex; min-width: 0; min-height: 40px; align-items: center; gap: var(--yp-space-3);
  box-sizing: border-box; border: 1px solid transparent; border-radius: var(--yp-radius-sm);
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard), border-color var(--yp-motion-fast) var(--yp-ease-standard);
}
.my-work-project__toggle { min-width: 0; height: 38px; padding: 0 var(--yp-space-3) 0 var(--yp-space-2); }
.my-work-project__open {
  flex: none; margin-left: auto; padding: 0 var(--yp-space-3); color: var(--yp-text-secondary); font-size: 12px;
  text-decoration: none; opacity: 0; transition: opacity var(--yp-motion-fast) var(--yp-ease-standard), color var(--yp-motion-fast) var(--yp-ease-standard);
}
.my-work-project__heading:hover .my-work-project__open,
.my-work-project__open:focus-visible { opacity: 1; }
.my-work-project__open:hover { color: var(--yp-link); }
.my-work-project--collapsed .my-work-project__heading {
  border-color: var(--yp-monday-grid-border); border-left: 6px solid var(--work-item-group-accent); background: var(--yp-bg-surface);
}
/* 与仪表板下钻相同：左侧留出行菜单列的位置。 */
.my-work-project__table { margin-top: var(--yp-space-2); padding-left: 32px; }
.my-work-project__placeholder { padding: var(--yp-space-3) 0; }
.my-work-project-enter-from,
.my-work-project-leave-to { opacity: 0; transform: translateY(8px); }
.my-work-project-enter-active,
.my-work-project-leave-active { transition: opacity var(--yp-motion-overlay) var(--yp-ease-standard), transform var(--yp-motion-overlay) var(--yp-ease-standard); }
@media (hover: none) { .my-work-project__open { opacity: 1; } }
@media (max-width: 600px) { .my-work-title span { display: none; } }
</style>
