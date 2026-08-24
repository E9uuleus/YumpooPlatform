<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import {
  AccountStatus, EmploymentStatus, ProjectActorAccess, ProjectLifecycleFilter, ProjectType,
  readCsrfToken, type Member, type ProjectOwnerOption, type ProjectPage, type ProjectTemplateVersion,
} from '@yumpoo/api-client'
import {
  ElButton, ElCheckbox, ElCheckboxGroup, ElDrawer, ElForm, ElFormItem, ElIcon, ElInput,
  ElLoading, ElMessage, ElMessageBox, ElOption as ElOptionRaw, ElPagination, ElSelect as ElSelectRaw,
  ElTable, ElTableColumn, ElTooltip, type FormInstance, type FormRules,
} from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, type DefineComponent } from 'vue'
import { useRouter } from 'vue-router'
import { identityAdministrationApi, projectsApi, projectTemplatesApi } from '../../api/client'
import { localProblem, toApiProblem, type ApiProblem } from '../../api/problems'
import InlineProblem from '../../components/InlineProblem.vue'
import ProjectWorkspaceHeader from '../../components/projects/ProjectWorkspaceHeader.vue'
import YpAssignee from '../../components/yp/YpAssignee.vue'
import YpEmptyState from '../../components/yp/YpEmptyState.vue'
import YpFilterBar from '../../components/yp/YpFilterBar.vue'
import YpStatusTag from '../../components/yp/YpStatusTag.vue'
import { useProjectRecents, type ProjectRecentSource } from '../../composables/useProjectRecents'
import { useSession } from '../../composables/useSession'
import { formatDateOnly, formatTimestamp } from '../../design-system/dates'
import { businessLabel } from '../../design-system/labels'
import type { ActiveFilter } from '../../design-system/types'

const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const vLoading = ElLoading.directive
type ModifiedPreset = 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'LAST_90_DAYS'
type ProjectCatalogView = 'recent' | 'content'

const router = useRouter()
const session = useSession()
const result = ref<ProjectPage>()
const templates = ref<ProjectTemplateVersion[]>([])
const owners = ref<Member[]>([])
const ownerOptions = ref<ProjectOwnerOption[]>([])
const activeView = ref<ProjectCatalogView>('content')
const query = ref('')
const appliedQuery = ref('')
const modifiedPreset = ref<ModifiedPreset>()
const projectTypes = ref<ProjectType[]>([])
const ownerUserIds = ref<string[]>([])
const actorAccesses = ref<ProjectActorAccess[]>([])
const page = ref(0)
const size = ref(20)
const loading = ref(false)
const error = ref<ApiProblem>()
const createOpen = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
let searchTimer: ReturnType<typeof setTimeout> | undefined

const companyTimezone = computed(() => session.authentication.value?.company.timezone ?? 'UTC')
const projectRecentScope = computed(() => {
  const authentication = session.authentication.value
  return authentication ? `${authentication.company.id}:${authentication.user.id}` : undefined
})
const projectRecents = useProjectRecents(() => projectRecentScope.value)
const recentProjects = projectRecents.items
const createForm = reactive({
  code: '', name: '', description: '', ownerUserId: '', templateVersionId: '', customerName: '',
  customerReference: '', deliverySite: '', contactNote: '',
})
const emptyCreateForm = JSON.stringify(createForm)
const createRules: FormRules = {
  templateVersionId: [{ required: true, message: '请选择模板版本', trigger: 'change' }],
  ownerUserId: [{ required: true, message: '请选择负责人', trigger: 'change' }],
  code: [{ required: true, whitespace: true, message: '请输入项目编码', trigger: 'blur' }],
  name: [{ required: true, whitespace: true, message: '请输入项目名称', trigger: 'blur' }],
}
const modifiedOptions: Array<{ value: ModifiedPreset; label: string; days: number }> = [
  { value: 'TODAY', label: '今天', days: 1 },
  { value: 'LAST_7_DAYS', label: '最近 7 天', days: 7 },
  { value: 'LAST_30_DAYS', label: '最近 30 天', days: 30 },
  { value: 'LAST_90_DAYS', label: '最近 90 天', days: 90 },
]
const projectTypeOptions = [ProjectType.ProductDevelopment, ProjectType.PreSales, ProjectType.Implementation, ProjectType.Hypercare]
const accessOptions = [ProjectActorAccess.Owner, ProjectActorAccess.Member, ProjectActorAccess.CompanyAdmin]
const createDirty = computed(() => JSON.stringify(createForm) !== emptyCreateForm)
const activeFilters = computed<ActiveFilter[]>(() => [
  ...(modifiedPreset.value ? [{ key: 'modified', label: '最后修改时间', valueLabel: modifiedOptions.find(item => item.value === modifiedPreset.value)?.label ?? '' }] : []),
  ...(projectTypes.value.length ? [{ key: 'types', label: '项目类型', valueLabel: projectTypes.value.map(businessLabel).join('、') }] : []),
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
    if (normalizedQuery && !`${item.name} ${item.code}`.toLocaleLowerCase().includes(normalizedQuery)) return false
    if (projectTypes.value.length && !projectTypes.value.includes(item.projectType)) return false
    if (ownerUserIds.value.length && !ownerUserIds.value.includes(item.ownerUserId)) return false
    if (actorAccesses.value.length && !actorAccesses.value.includes(item.actorAccess)) return false
    return since === undefined || new Date(item.updatedAt).getTime() >= since
  })
})
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
      ...(projectTypes.value.length ? { projectTypes: projectTypes.value } : {}),
      ...(ownerUserIds.value.length ? { ownerUserIds: ownerUserIds.value } : {}),
      ...(actorAccesses.value.length ? { actorAccesses: actorAccesses.value } : {}),
      ...(since ? { updatedSince: since } : {}),
      lifecycle: ProjectLifecycleFilter.All, page: page.value, size: size.value,
    })
  } catch (reason) {
    error.value = await toApiProblem(reason)
  } finally {
    loading.value = false
  }
}

async function loadReferenceData(): Promise<void> {
  try {
    const optionPromise = projectsApi.listProjectOwnerOptions()
    if (session.isCompanyAdmin.value) {
      const [options, templatePage, memberPage] = await Promise.all([
        optionPromise, projectTemplatesApi.listProjectTemplates(),
        identityAdministrationApi.listMembers({ employmentStatus: EmploymentStatus.Active, accountStatus: AccountStatus.Enabled, page: 0, size: 100 }),
      ])
      ownerOptions.value = options
      templates.value = templatePage.items
      owners.value = memberPage.items
    } else ownerOptions.value = await optionPromise
  } catch (reason) {
    error.value = await toApiProblem(reason)
  }
}

function refreshForFilters(): void { page.value = 0; void load() }
function selectModified(value: ModifiedPreset): void { modifiedPreset.value = modifiedPreset.value === value ? undefined : value; refreshForFilters() }
function removeFilter(key: string): void {
  if (key === 'modified') modifiedPreset.value = undefined
  if (key === 'types') projectTypes.value = []
  if (key === 'owners') ownerUserIds.value = []
  if (key === 'accesses') actorAccesses.value = []
  refreshForFilters()
}
function clearFilters(): void { modifiedPreset.value = undefined; projectTypes.value = []; ownerUserIds.value = []; actorAccesses.value = []; refreshForFilters() }
function scheduleSearch(): void {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(runSearchImmediately, 300)
}
function runSearchImmediately(): void { if (searchTimer) clearTimeout(searchTimer); appliedQuery.value = query.value.trim(); page.value = 0; void load() }
function handleSearchClear(): void { query.value = ''; runSearchImmediately() }
function selectView(view: ProjectCatalogView): void {
  activeView.value = view
  if (view === 'recent') projectRecents.refresh()
}
function openProject(project: ProjectRecentSource): void {
  projectRecents.record(project)
  void router.push({ name: 'project-overview', params: { projectId: project.id } })
}
function openProjectFromTable(row: unknown): void {
  openProject(row as ProjectRecentSource)
}
function openProjectById(projectId: string): void {
  const project = result.value?.items.find(item => item.id === projectId)
  if (project) openProject(project)
  else void router.push({ name: 'project-overview', params: { projectId } })
}
function resetCreateForm(): void { Object.assign(createForm, JSON.parse(emptyCreateForm) as typeof createForm); createFormRef.value?.clearValidate() }

async function beforeCreateClose(done: () => void): Promise<void> {
  if (!createDirty.value || creating.value) { done(); return }
  try {
    await ElMessageBox.confirm('当前 Project 草稿尚未创建，关闭后输入内容将丢失。', '放弃创建？', {
      confirmButtonText: '放弃输入', cancelButtonText: '继续编辑', type: 'warning',
    })
    done()
  } catch { /* 继续编辑 */ }
}
function requestCreateClose(): void { void beforeCreateClose(() => { createOpen.value = false }) }

async function createProject(): Promise<void> {
  try { await createFormRef.value?.validate() } catch { return }
  const csrf = readCsrfToken()
  const template = templates.value.find(item => item.templateVersionId === createForm.templateVersionId)
  if (!csrf || !template) { error.value = localProblem(csrf ? '模板版本不可用，请刷新后重试。' : '缺少 CSRF 凭据，请刷新后重试。'); return }
  creating.value = true
  error.value = undefined
  try {
    const project = await projectsApi.createProject({
      xXSRFTOKEN: csrf, idempotencyKey: crypto.randomUUID(),
      projectCreateRequest: {
        code: createForm.code.trim().toUpperCase(), name: createForm.name.trim(),
        description: createForm.description.trim() || null, projectType: template.projectType,
        ownerUserId: createForm.ownerUserId, templateKey: template.templateKey, templateVersion: template.version,
        customerName: createForm.customerName.trim() || null, customerReference: createForm.customerReference.trim() || null,
        deliverySite: createForm.deliverySite.trim() || null, contactNote: createForm.contactNote.trim() || null,
      },
    })
    ElMessage.success('Project 草稿已创建')
    resetCreateForm(); createOpen.value = false
    await Promise.all([load(), loadReferenceData()])
    openProjectById(project.id)
  } catch (reason) { error.value = await toApiProblem(reason) } finally { creating.value = false }
}

onMounted(async () => { await Promise.all([load(), loadReferenceData()]) })
onBeforeUnmount(() => { if (searchTimer) clearTimeout(searchTimer) })
</script>

<template>
  <section class="project-catalog">
    <project-workspace-header section="catalog" title="项目管理" description="统一查看项目状态、负责人、类型和协作角色。" />
    <inline-problem v-if="error" :problem="error" />

    <div class="project-list-surface">
      <nav class="project-catalog-tabs" role="tablist" aria-label="项目视图">
        <button
          id="project-recent-tab"
          class="project-catalog-tab"
          :class="{ selected: activeView === 'recent' }"
          type="button"
          role="tab"
          :aria-selected="activeView === 'recent'"
          aria-controls="project-recent-panel"
          :tabindex="activeView === 'recent' ? 0 : -1"
          @click="selectView('recent')"
          @keydown.right.prevent="selectView('content')"
        >
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M10 5.25v4.5l3 1.75M6.17 4.18H2.75v-3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M3.4 4.45A7.25 7.25 0 1 1 2.75 10" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
          </svg>
          <span>最近</span>
        </button>
        <button
          id="project-content-tab"
          class="project-catalog-tab"
          :class="{ selected: activeView === 'content' }"
          type="button"
          role="tab"
          :aria-selected="activeView === 'content'"
          aria-controls="project-content-panel"
          :tabindex="activeView === 'content' ? 0 : -1"
          @click="selectView('content')"
          @keydown.left.prevent="selectView('recent')"
        >
          <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            <path d="M5 2.75h6.75l3.25 3.5v8.25A2.5 2.5 0 0 1 12.5 17h-7A2.5 2.5 0 0 1 3 14.5V5.25A2.5 2.5 0 0 1 5.5 2.75Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
            <path d="M11.5 2.75v3.5H15M6.5 10h5M6.5 13h3.5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <span>内容</span>
        </button>
      </nav>

      <yp-filter-bar
        :filters="activeFilters" :result-count="visibleResultCount" :loading="loading"
        :popover-width="780" :show-tags="false" inline-search labeled-tools popover-class="project-filter-popover"
        @remove="removeFilter" @clear="clearFilters"
      >
        <template #search>
          <el-input v-model="query" clearable aria-label="搜索项目名称或编码" placeholder="搜索项目名称或编码"
            @input="scheduleSearch" @clear="handleSearchClear" @keyup.enter="runSearchImmediately" />
        </template>
        <template #filters>
          <div class="project-filter-grid">
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
              <h3>项目类型</h3>
              <el-checkbox-group v-model="projectTypes" @change="refreshForFilters">
                <el-tooltip
                  v-for="value in projectTypeOptions"
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
            <section class="project-filter-group">
              <h3>负责人</h3>
              <el-checkbox-group v-model="ownerUserIds" @change="refreshForFilters">
                <el-tooltip
                  v-for="owner in ownerOptions"
                  :key="owner.userId"
                  :content="owner.displayName"
                  placement="top"
                  :show-after="350"
                >
                  <el-checkbox :value="owner.userId">
                    <yp-assignee :user-id="owner.userId" :display-name="owner.displayName" size="table" />
                  </el-checkbox>
                </el-tooltip>
              </el-checkbox-group>
              <span v-if="!ownerOptions.length" class="project-filter-empty">暂无负责人选项</span>
            </section>
            <section class="project-filter-group">
              <h3>我的角色</h3>
              <el-checkbox-group v-model="actorAccesses" @change="refreshForFilters">
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
          <el-button v-if="session.isCompanyAdmin.value" type="primary" @click="createOpen = true"><el-icon><plus /></el-icon>创建项目</el-button>
        </template>
      </yp-filter-bar>

      <section
        v-if="activeView === 'content'"
        id="project-content-panel"
        role="tabpanel"
        aria-labelledby="project-content-tab"
      >
        <div v-if="loading || result?.items.length" v-loading="loading" class="project-table-shell" :aria-busy="loading" aria-live="polite">
          <div class="table-scroll project-desktop-table">
            <el-table class="project-management-table" :data="result?.items ?? []" @row-click="openProjectFromTable">
              <el-table-column fixed="left" label="项目名称" min-width="260">
                <template #default="scope">
                  <div class="project-name-cell">
                    <svg
                      class="project-name-cell__icon"
                      width="16"
                      height="16"
                      viewBox="0 0 16 16"
                      fill="none"
                      aria-hidden="true"
                    >
                      <rect x="2" y="2.5" width="12" height="11" rx="2" stroke="currentColor" stroke-width="1.3" />
                      <path d="M2 6.5h12M6.5 6.5v7" stroke="currentColor" stroke-width="1.3" />
                    </svg>
                    <button
                      class="project-name-cell__link"
                      type="button"
                      @click.stop="openProjectFromTable(scope.row)"
                    >
                      {{ scope.row.name }}
                    </button>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="110"><template #default="scope"><yp-status-tag domain="project-lifecycle" :status="scope.row.lifecycle" effect="soft" /></template></el-table-column>
              <el-table-column label="负责人" width="80"><template #default="scope"><yp-assignee :user-id="scope.row.ownerUserId" :display-name="scope.row.ownerDisplayName" size="table" :show-name="false" /></template></el-table-column>
              <el-table-column label="项目类型" width="130"><template #default="scope">{{ businessLabel(scope.row.projectType) }}</template></el-table-column>
              <el-table-column label="创建时间" width="120"><template #default="scope"><span :title="formatTimestamp(scope.row.createdAt, companyTimezone)">{{ formatDateOnly(scope.row.createdAt, companyTimezone) }}</span></template></el-table-column>
              <el-table-column label="最后修改时间" width="120"><template #default="scope"><span :title="formatTimestamp(scope.row.updatedAt, companyTimezone)">{{ formatDateOnly(scope.row.updatedAt, companyTimezone) }}</span></template></el-table-column>
              <el-table-column label="我的角色" width="110"><template #default="scope">{{ businessLabel(scope.row.actorAccess) }}</template></el-table-column>
            </el-table>
          </div>
          <ul class="project-mobile-list" aria-label="项目列表">
            <li v-for="item in result?.items ?? []" :key="item.id">
              <button class="project-mobile-row" type="button" @click="openProject(item)">
                <span class="project-mobile-row__header"><span class="project-mobile-row__identity"><strong>{{ item.name }}</strong></span><yp-status-tag domain="project-lifecycle" :status="item.lifecycle" size="small" effect="soft" /></span>
                <span class="project-mobile-row__meta"><yp-assignee :user-id="item.ownerUserId" :display-name="item.ownerDisplayName" size="table" :show-name="false" /><span>{{ businessLabel(item.projectType) }} · {{ businessLabel(item.actorAccess) }}</span></span>
                <span class="project-mobile-row__dates">创建 {{ formatDateOnly(item.createdAt, companyTimezone) }} · 修改 {{ formatDateOnly(item.updatedAt, companyTimezone) }}</span>
              </button>
            </li>
          </ul>
          <el-pagination v-if="result && result.totalElements > 0" class="page-control" layout="prev, pager, next, total"
            :current-page="page + 1" :page-size="size" :total="result.totalElements" @current-change="next => { page = next - 1; load() }" />
        </div>
        <yp-empty-state v-else reason="no-results" description="没有符合当前搜索和筛选条件的 Project。" compact>
          <template v-if="activeFilters.length" #action><el-button @click="clearFilters">清除筛选</el-button></template>
        </yp-empty-state>
      </section>

      <section
        v-else
        id="project-recent-panel"
        class="project-recent-shell"
        role="tabpanel"
        aria-labelledby="project-recent-tab"
      >
        <template v-if="recentProjectItems.length">
          <div class="project-recent-list__header" aria-hidden="true">
            <span>项目</span>
            <span>负责人</span>
            <span>项目类型</span>
            <span>最近打开</span>
            <span />
          </div>
          <ul class="project-recent-list" aria-label="最近打开的项目">
            <li v-for="item in recentProjectItems" :key="item.id" :class="{ pinned: item.pinned }">
              <button class="project-recent-list__identity" type="button" @click="openProject(item)">
                <svg width="20" height="20" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path d="M2.75 5.5c0-.966.784-1.75 1.75-1.75h3.19c.464 0 .91.184 1.238.513L10.165 5.5H15.5c.966 0 1.75.784 1.75 1.75v7.25a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-9Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
                </svg>
                <span><strong>{{ item.name }}</strong></span>
              </button>
              <yp-assignee :user-id="item.ownerUserId" :display-name="item.ownerDisplayName" size="table" :show-name="false" />
              <span class="project-recent-list__type">{{ businessLabel(item.projectType) }}</span>
              <time :datetime="new Date(item.openedAt).toISOString()" :title="formatTimestamp(new Date(item.openedAt), companyTimezone)">{{ formatDateOnly(new Date(item.openedAt), companyTimezone) }}</time>
              <button
                class="project-recent-list__pin"
                type="button"
                :class="{ active: item.pinned }"
                :aria-label="item.pinned ? `取消置顶项目 ${item.name}` : `置顶项目 ${item.name}`"
                :aria-pressed="item.pinned"
                @click="projectRecents.togglePinned(item.id)"
              >
                <svg width="20" height="20" viewBox="0 0 20 20" :fill="item.pinned ? 'currentColor' : 'none'" aria-hidden="true">
                  <path d="M7 2.75h6l-.9 4.05 2.4 2.4v1.3h-3.75v5.75L10 17.5l-.75-1.25V10.5H5.5V9.2l2.4-2.4L7 2.75Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
                </svg>
              </button>
            </li>
          </ul>
        </template>
        <yp-empty-state
          v-else
          :reason="recentProjects.length ? 'no-results' : 'empty'"
          :description="recentProjects.length ? '没有符合当前搜索和筛选条件的最近项目。' : '打开过的项目会按最近访问顺序显示在这里。'"
          compact
        >
          <template v-if="activeFilters.length" #action><el-button @click="clearFilters">清除筛选</el-button></template>
        </yp-empty-state>
      </section>
    </div>

    <el-drawer v-model="createOpen" class="project-create-drawer" title="创建 Project 草稿" size="min(620px, 100vw)" :before-close="beforeCreateClose" @closed="resetCreateForm">
      <el-form ref="createFormRef" label-position="top" :model="createForm" :rules="createRules">
        <el-form-item label="固化模板版本" prop="templateVersionId"><el-select v-model="createForm.templateVersionId" placeholder="请选择模板"><el-option v-for="item in templates" :key="item.templateVersionId" :label="`${item.displayName} / ${item.versionCode}`" :value="item.templateVersionId" /></el-select></el-form-item>
        <el-form-item label="负责人" prop="ownerUserId"><el-select v-model="createForm.ownerUserId" filterable placeholder="请选择负责人"><el-option v-for="item in owners" :key="item.userId" :label="item.displayName" :value="item.userId" /></el-select></el-form-item>
        <div class="form-grid"><el-form-item label="项目编码" prop="code"><el-input v-model="createForm.code" maxlength="32" /></el-form-item><el-form-item label="项目名称" prop="name"><el-input v-model="createForm.name" maxlength="80" /></el-form-item></div>
        <el-form-item label="描述"><el-input v-model="createForm.description" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="客户名称"><el-input v-model="createForm.customerName" maxlength="160" /><small class="muted-text">草稿阶段可空；非研发项目激活前必须补齐。</small></el-form-item>
        <div class="form-grid"><el-form-item label="客户参考号"><el-input v-model="createForm.customerReference" maxlength="80" /></el-form-item><el-form-item label="交付地点"><el-input v-model="createForm.deliverySite" maxlength="160" /></el-form-item></div>
        <el-form-item label="联系备注"><el-input v-model="createForm.contactNote" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <div class="action-row"><el-button @click="requestCreateClose">取消</el-button><el-button type="primary" :loading="creating" @click="createProject">创建草稿</el-button></div>
      </el-form>
    </el-drawer>
  </section>
</template>
