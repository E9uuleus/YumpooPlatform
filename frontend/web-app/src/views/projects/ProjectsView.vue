<script setup lang="ts">
import { usePageLoad } from '../../brand/page-progress'

import { vBrandLoading as vLoading } from '../../brand/loading'
import { Plus } from '@element-plus/icons-vue'
import { ProjectActorAccess, ProjectLifecycle, ProjectLifecycleFilter, type Project, type ProjectOwnerOption, type ProjectPage, type ProjectSummary } from '@yumpoo/api-client'
import {
  ElButton, ElCheckbox, ElCheckboxGroup, ElIcon, ElInput,
  ElMessage, ElPagination,
  ElTable, ElTableColumn, ElTooltip,
} from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { projectsApi } from '../../api/client'
import { isProblemStatus, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import { projectStatus } from '../../components/projects/projectStatus'
import ProjectCreateDialog from '../../components/projects/ProjectCreateDialog.vue'
import ProjectPinButton from '../../components/projects/ProjectPinButton.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import { projectColors } from '../../components/team/teamDashboardModel'
import { MY_PROJECT_ACCESSES } from '../../components/projects/projectScope'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpFilterBar from '../../components/yp/YpFilterBar.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import YpTabs, { type TabItem } from '../../components/yp/YpTabs.vue'
import { useProjectRecents, type ProjectRecentSource } from '../../composables/useProjectRecents'
import { useSession } from '../../composables/useSession'
import { usePageOnboarding } from '../../onboarding/useOnboarding'
import { formatDateOnly, formatTimestamp } from '../../design-system/dates'
import { businessLabel } from '../../design-system/labels'
import type { ActiveFilter } from '../../design-system/types'

const pageLoad = usePageLoad()
type StatusPreset = 'running' | 'ended'
const statusOptions: { value: StatusPreset; label: string }[] = [{ value: 'running', label: '进行中' }, { value: 'ended', label: '已结束' }]
const statusPreset = ref<StatusPreset>()
const endedFilter = computed(() => statusPreset.value ? statusPreset.value === 'ended' : undefined)
function selectStatus(value: StatusPreset): void { statusPreset.value = statusPreset.value === value ? undefined : value; refreshForFilters() }

type ModifiedPreset = 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'LAST_90_DAYS'
type ProjectCatalogView = 'recent' | 'content'

const router = useRouter()
const session = useSession()
const result = ref<ProjectPage>()
const colors = computed(() => projectColors((result.value?.items ?? []).map(item => item.id)))
const ownerOptions = ref<ProjectOwnerOption[]>([])
const referenceReady = ref(false)
const activeView = ref<ProjectCatalogView>('content')
const catalogTabs: TabItem[] = [
  { value: 'recent', label: '最近', id: 'project-recent-tab', controls: 'project-recent-panel' },
  { value: 'content', label: '内容', id: 'project-content-tab', controls: 'project-content-panel' },
]
const query = ref('')
const appliedQuery = ref('')
const modifiedPreset = ref<ModifiedPreset>()
const ownerUserIds = ref<string[]>([])
const actorAccesses = ref<ProjectActorAccess[]>([])
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<ApiProblem>()
const onboardingReady = computed(() => Boolean(result.value) && referenceReady.value && !loading.value && !error.value)
const onboarding = usePageOnboarding(() => onboardingReady.value)
watch(onboardingReady, ready => { if (ready) void onboarding.ready() }, { flush: 'post' })
const createOpen = ref(false)
const verifiedRecentIds = ref<Set<string>>(new Set())
const recentLoading = ref(false)
let searchTimer: ReturnType<typeof setTimeout> | undefined
let recentRevision = 0

const companyTimezone = computed(() => session.authentication.value?.company.timezone ?? 'UTC')
const projectRecentScope = computed(() => {
  const authentication = session.authentication.value
  return authentication ? `${authentication.company.id}:${authentication.user.id}` : undefined
})
const projectRecents = useProjectRecents(() => projectRecentScope.value)
watch(projectRecentScope, () => { recentRevision++; verifiedRecentIds.value = new Set(); recentLoading.value = false })
const recentProjects = projectRecents.items
watch(() => recentProjects.value.map(item => item.id), (next, previous) => {
  if (activeView.value === 'recent' && next.some(id => !previous.includes(id))) void selectView('recent')
})
const modifiedOptions: Array<{ value: ModifiedPreset; label: string; days: number }> = [
  { value: 'TODAY', label: '今天', days: 1 },
  { value: 'LAST_7_DAYS', label: '最近 7 天', days: 7 },
  { value: 'LAST_30_DAYS', label: '最近 30 天', days: 30 },
  { value: 'LAST_90_DAYS', label: '最近 90 天', days: 90 },
]
const accessOptions = MY_PROJECT_ACCESSES
const scopedAccesses = computed(() => actorAccesses.value.length ? actorAccesses.value : MY_PROJECT_ACCESSES)
const activeFilters = computed<ActiveFilter[]>(() => [
  ...(statusPreset.value ? [{ key: 'status', label: '项目状态', valueLabel: statusOptions.find(item => item.value === statusPreset.value)?.label ?? '' }] : []),
  ...(modifiedPreset.value ? [{ key: 'modified', label: '最后修改时间', valueLabel: modifiedOptions.find(item => item.value === modifiedPreset.value)?.label ?? '' }] : []),
  ...(ownerUserIds.value.length ? [{ key: 'owners', label: '负责人', valueLabel: ownerUserIds.value.map(id => ownerOptions.value.find(item => item.userId === id)?.displayName ?? id).join('、') }] : []),
  ...(actorAccesses.value.length ? [{ key: 'accesses', label: '我的角色', valueLabel: actorAccesses.value.map(businessLabel).join('、') }] : []),
])

function localDateParts(date: Date, timezone: string) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(date)
  const value = (type: Intl.DateTimeFormatPartTypes) => Number(parts.find(part => part.type === type)?.value)
  return { year: value('year'), month: value('month'), day: value('day') }
}

function localMidnightInstant(timezone: string, daysAgo: number): Date {
  const today = localDateParts(new Date(), timezone)
  const wallClock = Date.UTC(today.year, today.month - 1, today.day - daysAgo)
  let instant = wallClock
  for (let index = 0; index < 2; index += 1) {
    const represented = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
    }).formatToParts(new Date(instant))
    const part = (type: Intl.DateTimeFormatPartTypes) => Number(represented.find(item => item.type === type)?.value)
    instant -= Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second')) - wallClock
  }
  return new Date(instant)
}

function updatedSince(): Date | undefined {
  const option = modifiedOptions.find(item => item.value === modifiedPreset.value)
  return option ? localMidnightInstant(companyTimezone.value, option.days - 1) : undefined
}

const recentProjectItems = computed(() => {
  const normalizedQuery = appliedQuery.value.toLocaleLowerCase()
  const since = updatedSince()?.getTime()
  return recentProjects.value.filter(item => {
    if (!verifiedRecentIds.value.has(item.id)) return false
    if (normalizedQuery && !`${item.name} ${item.code}`.toLocaleLowerCase().includes(normalizedQuery)) return false
    if (item.lifecycle !== ProjectLifecycle.Active) return false
    if (endedFilter.value !== undefined && Boolean(item.endedAt) !== endedFilter.value) return false
    if (ownerUserIds.value.length && !ownerUserIds.value.includes(item.ownerUserId)) return false
    if (!scopedAccesses.value.includes(item.actorAccess)) return false
    return since === undefined || new Date(item.updatedAt).getTime() >= since
  })
})
const recentColors = computed(() => projectColors(recentProjectItems.value.map(item => item.id)))
const visibleResultCount = computed(() => activeView.value === 'recent'
  ? recentProjectItems.value.length
  : result.value?.totalElements ?? null)

async function load(): Promise<void> {
  loading.value = true
  error.value = undefined
  const since = updatedSince()
  try {
    result.value = await projectsApi.listProjects({
      ...(appliedQuery.value ? { query: appliedQuery.value } : {}),
      ...(endedFilter.value === undefined ? {} : { ended: endedFilter.value }),
      ...(ownerUserIds.value.length ? { ownerUserIds: ownerUserIds.value } : {}),
      actorAccesses: scopedAccesses.value,
      ...(since ? { updatedSince: since } : {}),
      lifecycle: ProjectLifecycleFilter.Active, page: page.value, size: size.value,
    })
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function loadReferenceData(): Promise<void> {
  try {
    ownerOptions.value = await projectsApi.listProjectOwnerOptions()
    referenceReady.value = true
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

function refreshForFilters(): void { page.value = 0; void load() }
function selectModified(value: ModifiedPreset): void { modifiedPreset.value = modifiedPreset.value === value ? undefined : value; refreshForFilters() }
function removeFilter(key: string): void {
  if (key === 'status') statusPreset.value = undefined
  if (key === 'modified') modifiedPreset.value = undefined
  if (key === 'owners') ownerUserIds.value = []
  if (key === 'accesses') actorAccesses.value = []
  refreshForFilters()
}
function clearFilters(): void { statusPreset.value = undefined; modifiedPreset.value = undefined; ownerUserIds.value = []; actorAccesses.value = []; refreshForFilters() }
function scheduleSearch(): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(runSearchImmediately, 300)
}
function runSearchImmediately(): void { if (searchTimer) clearTimeout(searchTimer); appliedQuery.value = query.value.trim(); page.value = 0; void load() }
function handleSearchClear(): void { query.value = ''; runSearchImmediately() }
async function selectView(view: ProjectCatalogView): Promise<void> {
  const revision = ++recentRevision, scope = projectRecentScope.value
  activeView.value = view
  if (view !== 'recent') return
  projectRecents.refresh()
  recentLoading.value = true
  verifiedRecentIds.value = new Set()
  const verified: ProjectRecentSource[] = [], removed: string[] = []
  let failure: ApiProblem | undefined
  try {
    await Promise.all(recentProjects.value.map(async recent => {
      try {
        const current = await projectsApi.getProject({ projectId: recent.id })
        if (current.lifecycle === ProjectLifecycle.Active) verified.push(current)
        else removed.push(recent.id)
      } catch (reason) {
        const problem = await toApiProblem(reason)
        if (isProblemStatus(problem, 404)) removed.push(recent.id)
        else failure = problem
      }
    }))
    if (revision !== recentRevision || scope !== projectRecentScope.value) return
    if (failure) error.value = failure
    projectRecents.reconcile(verified, removed)
    verifiedRecentIds.value = new Set(verified.map(project => project.id))
  } finally { if (revision === recentRevision) recentLoading.value = false }
}
function openProject(project: ProjectRecentSource): void {
  projectRecents.record(project)
  void router.push({ name: 'project-overview', params: { projectId: project.id } })
}
function openProjectFromTable(row: unknown): void {
  openProject(row as ProjectRecentSource)
}
function projectCreated(project: Project): void {
  const now = new Date()
  projectRecents.record({ ...project, ownerDisplayName: session.authentication.value?.user.displayName ?? '我',
    actorAccess: ProjectActorAccess.Owner, createdAt: now, updatedAt: now })
  ElMessage.success('项目已创建')
  void router.push({ name: 'project-overview', params: { projectId: project.id } })
}

onMounted(async () => {
  await pageLoad.track(() => Promise.all([load(), loadReferenceData()]))
})
onBeforeUnmount(() => { recentRevision++; if (searchTimer) clearTimeout(searchTimer) })
</script>

<template>
  <section class="project-catalog">
    <project-workspace-header
      section="catalog"
      title="管理项目"
    />
    <inline-problem
      v-if="error"
      :problem="error"
    />

    <div class="project-list-surface">
      <yp-tabs
        class="project-catalog-tabs"
        data-tour="project-catalog-tabs"
        label="项目视图"
        :items="catalogTabs"
        :model-value="activeView"
        @update:model-value="selectView($event as ProjectCatalogView)"
      >
        <template #icon-recent>
          <svg
            width="20"
            height="20"
            viewBox="0 0 20 20"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M10 5.25v4.5l3 1.75M6.17 4.18H2.75v-3"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
            <path
              d="M3.4 4.45A7.25 7.25 0 1 1 2.75 10"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
            />
          </svg>
        </template>
        <template #icon-content>
          <svg
            width="20"
            height="20"
            viewBox="0 0 20 20"
            fill="none"
            aria-hidden="true"
          >
            <path
              d="M5 2.75h6.75l3.25 3.5v8.25A2.5 2.5 0 0 1 12.5 17h-7A2.5 2.5 0 0 1 3 14.5V5.25A2.5 2.5 0 0 1 5.5 2.75Z"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linejoin="round"
            />
            <path
              d="M11.5 2.75v3.5H15M6.5 10h5M6.5 13h3.5"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </template>
      </yp-tabs>

      <yp-filter-bar
        data-tour="project-filter"
        :filters="activeFilters"
        :result-count="visibleResultCount"
        :loading="loading"
        :popover-width="780"
        :show-tags="false"
        inline-search
        labeled-tools
        popover-class="project-filter-popover"
        @remove="removeFilter"
        @clear="clearFilters"
      >
        <template #search>
          <el-input
            v-model="query"
            clearable
            aria-label="搜索项目名称或编码"
            placeholder="搜索项目名称或编码"
            @input="scheduleSearch"
            @clear="handleSearchClear"
            @keyup.enter="runSearchImmediately"
          />
        </template>
        <template #filters>
          <div class="project-filter-grid">
            <section class="project-filter-group">
              <h3>项目状态</h3>
              <button
                v-for="option in statusOptions"
                :key="option.value"
                type="button"
                class="project-filter-option"
                :class="{ selected: statusPreset === option.value }"
                :aria-pressed="statusPreset === option.value"
                @click="selectStatus(option.value)"
              >
                {{ option.label }}
              </button>
            </section>
            <section class="project-filter-group">
              <h3>最后修改时间</h3>
              <el-tooltip
                v-for="option in modifiedOptions"
                :key="option.value"
                :content="option.label"
                placement="top"
                :show-after="350"
              >
                <button
                  type="button"
                  class="project-filter-option"
                  :class="{ selected: modifiedPreset === option.value }"
                  :aria-pressed="modifiedPreset === option.value"
                  @click="selectModified(option.value)"
                >
                  {{ option.label }}
                </button>
              </el-tooltip>
            </section>
            <section class="project-filter-group">
              <h3>负责人</h3>
              <el-checkbox-group
                v-model="ownerUserIds"
                @change="refreshForFilters"
              >
                <el-tooltip
                  v-for="owner in ownerOptions"
                  :key="owner.userId"
                  :content="owner.displayName"
                  placement="top"
                  :show-after="350"
                >
                  <el-checkbox :value="owner.userId">
                    <yp-assignee
                      :user-id="owner.userId"
                      :display-name="owner.displayName"
                      size="table"
                    />
                  </el-checkbox>
                </el-tooltip>
              </el-checkbox-group>
              <span
                v-if="!ownerOptions.length"
                class="project-filter-empty"
              >暂无负责人选项</span>
            </section>
            <section class="project-filter-group">
              <h3>我的角色</h3>
              <el-checkbox-group
                v-model="actorAccesses"
                @change="refreshForFilters"
              >
                <el-tooltip
                  v-for="value in accessOptions"
                  :key="value"
                  :content="businessLabel(value)"
                  placement="top"
                  :show-after="350"
                >
                  <el-checkbox :value="value">
                    {{ businessLabel(value) }}
                  </el-checkbox>
                </el-tooltip>
              </el-checkbox-group>
            </section>
          </div>
        </template>
        <template #actions>
          <el-button
            type="primary"
            data-tour="project-create"
            @click="createOpen = true"
          >
            <el-icon><plus /></el-icon>创建项目
          </el-button>
        </template>
      </yp-filter-bar>

      <section
        v-if="activeView === 'content'"
        id="project-content-panel"
        role="tabpanel"
        aria-labelledby="project-content-tab"
      >
        <div
          v-if="loading || result?.items.length"
          v-loading="loading"
          class="project-table-shell"
          :aria-busy="loading"
          aria-live="polite"
        >
          <div class="table-scroll project-desktop-table">
            <el-table
              class="project-management-table project-management-table--catalog"
              :data="result?.items ?? []"
              @row-click="openProjectFromTable"
            >
              <el-table-column
                fixed="left"
                label="项目名称"
                min-width="360"
              >
                <template #default="scope">
                  <div class="project-identity-cell">
                    <span
                      class="project-identity-cell__mark"
                      :style="{ '--mark': colors.get(scope.row.id) }"
                      aria-hidden="true"
                    >{{ scope.row.name.slice(0, 1) }}</span>
                    <span class="project-identity-cell__text">
                      <button
                        class="project-name-cell__link"
                        type="button"
                        @click.stop="openProjectFromTable(scope.row)"
                      >{{ scope.row.name }}</button>
                      <small>
                        <span>负责人 {{ scope.row.ownerDisplayName }}</span>
                        <span :title="formatTimestamp(scope.row.createdAt, companyTimezone)">创建 {{ formatDateOnly(scope.row.createdAt, companyTimezone) }}</span>
                        <span :title="formatTimestamp(scope.row.updatedAt, companyTimezone)">修改 {{ formatDateOnly(scope.row.updatedAt, companyTimezone) }}</span>
                      </small>
                    </span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                label="状态"
                width="120"
                align="center"
              >
                <template #default="scope">
                  <yp-status-tag
                    domain="project-lifecycle"
                    :status="projectStatus(scope.row as ProjectSummary)"
                  />
                </template>
              </el-table-column>
              <el-table-column
                label="我的角色"
                width="120"
                align="center"
              >
                <template #default="scope">
                  <span class="project-role-chip">{{ businessLabel(scope.row.actorAccess) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <ul
            class="project-mobile-list"
            aria-label="项目列表"
          >
            <li
              v-for="item in result?.items ?? []"
              :key="item.id"
            >
              <button
                class="project-mobile-row"
                type="button"
                @click="openProject(item)"
              >
                <span class="project-mobile-row__header"><span class="project-mobile-row__identity"><strong>{{ item.name }}</strong></span><yp-status-tag
                  domain="project-lifecycle"
                  :status="projectStatus(item)"
                  size="small"
                /></span>
                <span class="project-mobile-row__meta"><yp-assignee
                  :user-id="item.ownerUserId"
                  :display-name="item.ownerDisplayName"
                  size="table"
                  :show-name="false"
                /><span>{{ businessLabel(item.actorAccess) }}</span></span>
                <span class="project-mobile-row__dates">创建 {{ formatDateOnly(item.createdAt, companyTimezone) }} · 修改 {{ formatDateOnly(item.updatedAt, companyTimezone) }}</span>
              </button>
            </li>
          </ul>
          <el-pagination
            v-if="result && result.totalElements > 0"
            class="page-control"
            layout="prev, pager, next, total"
            :current-page="page + 1"
            :page-size="size"
            :total="result.totalElements"
            @current-change="next => { page = next - 1; load() }"
          />
        </div>
        <yp-empty-state
          v-else
          reason="no-results"
          description="没有符合当前搜索和筛选条件的项目。"
          compact
        >
          <template
            v-if="activeFilters.length"
            #action
          >
            <el-button @click="clearFilters">
              清除筛选
            </el-button>
          </template>
        </yp-empty-state>
      </section>

      <section
        v-else
        id="project-recent-panel"
        role="tabpanel"
        aria-labelledby="project-recent-tab"
      >
        <div
          v-if="recentLoading || recentProjectItems.length"
          v-loading="recentLoading"
          class="project-table-shell"
          :aria-busy="recentLoading"
          aria-live="polite"
        >
          <div class="table-scroll project-desktop-table">
            <el-table
              class="project-management-table project-management-table--catalog"
              :data="recentProjectItems"
              row-key="id"
              @row-click="openProjectFromTable"
            >
              <el-table-column
                fixed="left"
                label="项目名称"
                min-width="360"
              >
                <template #default="scope">
                  <div class="project-identity-cell">
                    <span
                      class="project-identity-cell__mark"
                      :style="{ '--mark': recentColors.get(scope.row.id) }"
                      aria-hidden="true"
                    >{{ scope.row.name.slice(0, 1) }}</span>
                    <span class="project-identity-cell__text">
                      <button
                        class="project-name-cell__link"
                        type="button"
                        @click.stop="openProjectFromTable(scope.row)"
                      >{{ scope.row.name }}</button>
                      <small>
                        <span>负责人 {{ scope.row.ownerDisplayName }}</span>
                        <span
                          v-if="scope.row.openedAt"
                          :title="formatTimestamp(new Date(scope.row.openedAt), companyTimezone)"
                        >最近打开 {{ formatDateOnly(new Date(scope.row.openedAt), companyTimezone) }}</span>
                      </small>
                    </span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column
                label="状态"
                width="120"
                align="center"
              >
                <template #default="scope">
                  <yp-status-tag
                    domain="project-lifecycle"
                    :status="projectStatus(scope.row as ProjectSummary)"
                  />
                </template>
              </el-table-column>
              <el-table-column
                label="我的角色"
                width="120"
                align="center"
              >
                <template #default="scope">
                  <span class="project-role-chip">{{ businessLabel(scope.row.actorAccess) }}</span>
                </template>
              </el-table-column>
              <el-table-column
                width="64"
                align="center"
              >
                <template #default="scope">
                  <project-pin-button
                    :pinned="scope.row.pinned"
                    :name="scope.row.name"
                    @toggle="projectRecents.togglePinned(scope.row as ProjectRecentSource)"
                  />
                </template>
              </el-table-column>
            </el-table>
          </div>
          <ul
            class="project-mobile-list"
            aria-label="最近打开的项目"
          >
            <li
              v-for="item in recentProjectItems"
              :key="item.id"
              class="project-mobile-list__item--pinnable"
            >
              <button
                class="project-mobile-row"
                type="button"
                @click="openProject(item)"
              >
                <span class="project-mobile-row__header"><span class="project-mobile-row__identity"><strong>{{ item.name }}</strong></span><yp-status-tag
                  domain="project-lifecycle"
                  :status="projectStatus(item)"
                  size="small"
                /></span>
                <span class="project-mobile-row__meta"><yp-assignee
                  :user-id="item.ownerUserId"
                  :display-name="item.ownerDisplayName"
                  size="table"
                  :show-name="false"
                /><span>{{ businessLabel(item.actorAccess) }}</span></span>
                <span
                  v-if="item.openedAt"
                  class="project-mobile-row__dates"
                >最近打开 {{ formatDateOnly(new Date(item.openedAt), companyTimezone) }}</span>
              </button>
              <project-pin-button
                :pinned="item.pinned"
                :name="item.name"
                @toggle="projectRecents.togglePinned(item)"
              />
            </li>
          </ul>
        </div>
        <yp-empty-state
          v-else
          :reason="recentProjects.length ? 'no-results' : 'empty'"
          :description="recentProjects.length ? '没有符合当前搜索和筛选条件的最近项目。' : '打开过的项目会按最近访问顺序显示在这里。'"
          compact
        >
          <template
            v-if="activeFilters.length"
            #action
          >
            <el-button @click="clearFilters">
              清除筛选
            </el-button>
          </template>
        </yp-empty-state>
      </section>
    </div>

    <project-create-dialog
      v-model="createOpen"
      @created="projectCreated"
    />
  </section>
</template>
