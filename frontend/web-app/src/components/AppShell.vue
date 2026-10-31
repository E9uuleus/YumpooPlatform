<script setup lang="ts">
import { Calendar, DataLine, DataAnalysis, Box, Clock, Close, FolderOpened, Grid, House, Menu as MenuIcon, Search, Setting, User, OfficeBuilding, Monitor } from '@element-plus/icons-vue'
import { AuthenticationClientType, ProjectLifecycleFilter, ProjectActorAccess, type Project, type ProjectSummary } from '@yumpoo/api-client'
import {
  ElButton,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElIcon,
  ElMessage,
  ElTooltip,
} from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, provide, readonly, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { operationsApi, projectsApi } from '../api/client'
import { beginAuthentication } from '../auth/navigation'
import { orderByRecents, useProjectRecents } from '../composables/useProjectRecents'
import { useReleaseVersion } from '../composables/useReleaseVersion'
import { PROJECT_LIFECYCLE_CHANGED } from '../composables/projectLifecycleEvents'
import { PROJECT_SETTINGS_CHANGED } from '../composables/projectSettingsEvents'
import { useSession } from '../composables/useSession'
import { useOperationsQuery } from '../composables/useOperationsQuery'
import { useInbox, type InboxFilter } from '../composables/useInbox'
import OnboardingHost from '../onboarding/OnboardingHost.vue'
import { useOnboarding } from '../onboarding/useOnboarding'
import { INBOX_FILTERS, parseInboxFilter } from './inbox/inboxPresentation'
import InboxBell from './inbox/InboxBell.vue'
import OperationsBadge from './operations/OperationsBadge.vue'
import { operationsAlertSummaryContext } from './operations/operationsContext'
import type { ShellSection } from '../router/shell-navigation'
import DesktopUpdateBanner from './desktop/DesktopUpdateBanner.vue'
import InlineProblem from './InlineProblem.vue'
import ProjectCreateDialog from './projects/ProjectCreateDialog.vue'
import { MY_PROJECT_ACCESSES } from './projects/projectScope'
import YpAssignee from './yp/YpAssignee.vue'
import brandLogo from '../assets/brand/logo.svg'
import { openDocs } from '../docs-site/openDocs'
import { routeHelpPath } from '../docs-site/routeHelp'
import ShellIcon from './shell/ShellIcon.vue'

interface ModuleItem {
  section: ShellSection
  label: string
  routeName: 'home' | 'workspace' | 'team-entry' | 'company-overview' | 'operations-overview'
  icon: Component
}

interface ContextItem {
  label: string
  routeName: string
  params?: Record<string, string>
  query?: Record<string, string>
  badge?: number
  icon?: Component
  groupLabel?: string
}

const route = useRoute()
const router = useRouter()
const session = useSession()
const inbox = useInbox()
const onboarding = useOnboarding()
const routeTour = computed(() => route.meta.onboardingTour)
const mobileNavigationOpen = ref(false)
const projectNavigationOpen = ref(true)
const projectCreateOpen = ref(false)
const sidebarSearchOpen = ref(false)
const sidebarSearchQuery = ref('')
const desktopSearchInput = ref<HTMLInputElement>()
const mobileSearchInput = ref<HTMLInputElement>()
const navigationProjects = ref<ProjectSummary[]>([])
const hasMoreNavigationProjects = ref(false)
const navigationProjectsFailed = ref(false)
const contextNavigationOpen = ref(typeof window === 'undefined'
  ? true
  : (window.matchMedia?.('(min-width: 1280px)').matches ?? true))

const inDesktopShell = computed(() => window.yumpooDesktop?.client === 'electron' || session.authentication.value?.client.type === AuthenticationClientType.Electron)
const routeHelp = computed(() => routeHelpPath(route.name))
const helpMenuOpen = ref(false)

function openHelp(command: string): void {
  const href = command === 'page' ? routeHelp.value : command
  if (href) openDocs(router, href)
}
const releaseVersion = useReleaseVersion()
const releaseLabel = computed(() => releaseVersion.value
  ? `版本 ${releaseVersion.value.release} · ${releaseVersion.value.commit.slice(0, 8)}`
  : '')
const activeSection = computed<ShellSection>(() => route.meta.shellSection ?? 'work')
const myTimeActive = computed(() => route.name === 'my-time' || route.name === 'my-time-day')
const fixedFrame = computed(() => route.meta.frame === 'fixed')
const isWorkspaceSection = computed(() => activeSection.value === 'work')
const hasContextNavigation = computed(() => activeSection.value !== 'home')
const projectRecentScope = computed(() => {
  const authentication = session.authentication.value
  return authentication ? `${authentication.company.id}:${authentication.user.id}` : undefined
})
const projectRecents = useProjectRecents(() => projectRecentScope.value)
const NAVIGATION_PROJECT_LIMIT = 10
/** 置顶 → 最近打开 → 其余按名称，与「管理项目 → 最近」同一顺序。 */
const displayedNavigationProjects = computed(() => orderByRecents(navigationProjects.value, projectRecents.items.value)
  .slice(0, NAVIGATION_PROJECT_LIMIT))
const pinnedProjectIds = computed(() => new Set(projectRecents.items.value.filter(item => item.pinned).map(item => item.id)))
const topRecentIds = computed(() => projectRecents.items.value.slice(0, NAVIGATION_PROJECT_LIMIT).map(item => item.id))
let requestedRecentIds = new Set<string>()
const operationsAlerts = useOperationsQuery((signal) => operationsApi.getOperationsAlertSummary({ signal }), {
  interval: () => 60000,
  enabled: () => session.isPlatformAdmin.value && session.phase.value === 'authenticated',
})
const operationsAlertCount = computed(() => (operationsAlerts.data.value?.critical ?? 0) + (operationsAlerts.data.value?.warning ?? 0))
provide(operationsAlertSummaryContext, readonly(operationsAlerts.data))
function operationsAlertsChanged() { void operationsAlerts.refresh() }
onMounted(() => window.addEventListener('yumpoo-operations-alerts-changed', operationsAlertsChanged))
onBeforeUnmount(() => window.removeEventListener('yumpoo-operations-alerts-changed', operationsAlertsChanged))
const moduleItems = computed<ModuleItem[]>(() => [
  { section: 'home', label: '首页', routeName: 'home', icon: House },
  { section: 'work', label: '工作台', routeName: 'workspace', icon: Grid },
  ...(session.isCompanyAdmin.value ? [{ section: 'team', label: '团队', routeName: 'team-entry', icon: DataLine } as const] : []),
  ...(session.isIdentityReader.value
    ? [{ section: 'company', label: '公司管理', routeName: 'company-overview', icon: OfficeBuilding } as const]
    : []),
  ...(session.isPlatformAdmin.value ? [{ section: 'operations', label: '运维', routeName: 'operations-overview', icon: Monitor } as const] : []),
])
const contextTitle = computed(() => ({
  home: '首页',
  work: '工作台',
  team: '团队视图',
  company: '公司管理',
  inbox: '收件箱',
  operations: '运维中心',
})[activeSection.value])
const contextItems = computed<ContextItem[]>(() => {
  if (activeSection.value === 'operations') return [
    { label: '概览', routeName: 'operations-overview' },
    { label: '主机与运行时', routeName: 'operations-host' },
    { label: '在线会话', routeName: 'operations-sessions' },
    { label: '运行日志', routeName: 'operations-logs' },
    { label: '告警', routeName: 'operations-alerts', badge: operationsAlertCount.value },
  ]
  if (activeSection.value === 'inbox') {
    const counts = inbox.counts.value
    const badges: Partial<Record<InboxFilter, number | undefined>> = {
      unread: counts?.total, mention: counts?.mention, comment: counts?.comment, assigned: counts?.assigned, project: counts?.project,
    }
    return INBOX_FILTERS.map(({ value, label }) => ({ label, routeName: 'inbox', query: { filter: value }, badge: badges[value] ?? 0 }))
  }
  if (activeSection.value === 'team') return [
    { label: '成员工时', routeName: 'team', params: { view: 'timesheet' } },
    { label: '项目工时', routeName: 'team', params: { view: 'projects' } },
    { label: '加班统计', routeName: 'team', params: { view: 'overtime' } },
    { label: '成员当前任务', routeName: 'team', params: { view: 'workload' } },
  ]
  if (activeSection.value === 'company') {
    return [
      { label: '概览', routeName: 'company-overview' },
      { label: '项目', routeName: 'company-projects' },
      { label: '成员与角色', routeName: 'company-members' },
      { label: '活动日志', routeName: 'company-activity' },
      { label: '同步运行', routeName: 'company-sync-runs' },
      { label: '定时任务', routeName: 'company-scheduled-jobs' },
      { label: '工作日历', routeName: 'company-work-calendar' },
    ]
  }
  if (isWorkspaceSection.value) {
    const items: ContextItem[] = []
    const projectId = typeof route.params.projectId === 'string' ? route.params.projectId : undefined
    if (projectId) {
      items.push(
        { label: '工作项', routeName: 'project-overview', params: { projectId }, icon: Grid, groupLabel: '当前项目' },
        { label: '统计', routeName: 'project-statistics', params: { projectId }, icon: DataAnalysis },
        { label: '成员', routeName: 'project-members', params: { projectId }, icon: User },
        { label: '动态', routeName: 'project-activity', params: { projectId }, icon: Clock },
        { label: '设置', routeName: 'project-settings', params: { projectId }, icon: Setting },
      )
    }
    return items
  }
  return []
})

function navigate(name: string, params?: Record<string, string>, query?: Record<string, string>): void {
  mobileNavigationOpen.value = false
  if (name === 'workspace' || name === 'dashboards' || name === 'my-time') {
    const workspaceSlug = session.authentication.value?.user.workspaceSlug
    if (workspaceSlug) void router.push({ name, params: { workspaceSlug } })
    return
  }
  void router.push({ name, ...(params ? { params } : {}), ...(query ? { query } : {}) })
}

function navigateProject(project: ProjectSummary): void {
  resetSidebarSearch()
  projectRecents.record(project)
  navigate('project-overview', { projectId: project.id })
}

function isContextItemActive(item: ContextItem): boolean {
  // 迁入工时 is a sub-page of 成员工时.
  if (route.name === 'team-hours-import') return item.routeName === 'team' && item.params?.view === 'timesheet'
  return route.name === item.routeName
    && (!item.params?.view || route.params.view === item.params.view)
    && (!item.query?.filter || parseInboxFilter(route.query.filter) === item.query.filter)
}

async function signOut(): Promise<void> {
  const target = route.fullPath
  if (await session.logout()) beginAuthentication(target)
}

let navigationRequest = 0
let sidebarSearchTimer: ReturnType<typeof setTimeout> | undefined

async function loadNavigationProjects(query = sidebarSearchQuery.value.trim()): Promise<void> {
  const request = ++navigationRequest
  navigationProjectsFailed.value = false
  const filters = { lifecycle: ProjectLifecycleFilter.Active, ended: false, actorAccesses: MY_PROJECT_ACCESSES }
  const activeProjectId = typeof route.params.projectId === 'string' ? route.params.projectId : undefined
  // 最近与置顶只存在本机，由服务端按同样的筛选确认它们仍可见，再与按名称排列的项目合并。
  const recentIds = query ? [] : [...new Set([...(activeProjectId ? [activeProjectId] : []), ...topRecentIds.value])]
  try {
    const [projectPage, recentPage] = await Promise.all([
      projectsApi.listProjects({ ...(query ? { query } : {}), ...filters, page: 0, size: NAVIGATION_PROJECT_LIMIT + 1 }),
      recentIds.length ? projectsApi.listProjects({ ...filters, ids: recentIds, page: 0, size: recentIds.length }) : undefined,
    ])
    if (request !== navigationRequest) return
    requestedRecentIds = new Set(recentIds)
    navigationProjects.value = [...new Map([...(recentPage?.items ?? []), ...projectPage.items]
      .map(project => [project.id, project])).values()]
    hasMoreNavigationProjects.value = projectPage.totalElements > NAVIGATION_PROJECT_LIMIT
    const activeProject = recentPage?.items.find(project => project.id === activeProjectId)
    if (activeProject) projectRecents.record(activeProject)
  } catch {
    if (request !== navigationRequest) return
    navigationProjects.value = []
    hasMoreNavigationProjects.value = false
    navigationProjectsFailed.value = true
  }
}

function projectCreated(project: Project): void {
  const now = new Date()
  projectRecents.record({ ...project, ownerDisplayName: session.authentication.value?.user.displayName ?? '我',
    actorAccess: ProjectActorAccess.Owner, createdAt: now, updatedAt: now })
  ElMessage.success('项目已创建')
  void loadNavigationProjects()
  void router.push({ name: 'project-overview', params: { projectId: project.id } })
}

function openSidebarSearch(): void {
  sidebarSearchOpen.value = true
  projectNavigationOpen.value = true
  void nextTick(() => {
    desktopSearchInput.value?.focus()
    mobileSearchInput.value?.focus()
  })
}

function scheduleSidebarSearch(): void {
  navigationRequest += 1
  if (sidebarSearchTimer) clearTimeout(sidebarSearchTimer)
  sidebarSearchTimer = setTimeout(() => {
    sidebarSearchTimer = undefined
    void loadNavigationProjects()
  }, 250)
}

function resetSidebarSearch(): void {
  if (sidebarSearchTimer) clearTimeout(sidebarSearchTimer)
  sidebarSearchTimer = undefined
  navigationRequest += 1
  sidebarSearchOpen.value = false
  sidebarSearchQuery.value = ''
}

function closeSidebarSearch(): void {
  resetSidebarSearch()
  if (isWorkspaceSection.value) void loadNavigationProjects('')
}

function collapseContextNavigation(): void {
  resetSidebarSearch()
  contextNavigationOpen.value = false
}

function expandContextNavigation(): void {
  contextNavigationOpen.value = true
}

function closeMobileNavigation(): void {
  resetSidebarSearch()
  mobileNavigationOpen.value = false
}

watch(
  [isWorkspaceSection, () => route.params.projectId],
  ([workspaceSection]) => {
    if (workspaceSection) {
      void loadNavigationProjects()
    } else {
      resetSidebarSearch()
    }
  },
  { immediate: true },
)

// 在「管理项目 → 最近」中置顶了菜单里还没有的项目时，补取一次。
watch(topRecentIds, ids => {
  if (!isWorkspaceSection.value || sidebarSearchQuery.value.trim()) return
  const loaded = new Set(navigationProjects.value.map(project => project.id))
  if (ids.some(id => !loaded.has(id) && !requestedRecentIds.has(id))) void loadNavigationProjects()
})

function lifecycleChanged(): void {
  if (isWorkspaceSection.value) void loadNavigationProjects()
}
function projectSettingsChanged(event: Event): void {
  const project = (event as CustomEvent<ProjectSummary>).detail
  navigationProjects.value = navigationProjects.value.map(item => item.id === project.id ? { ...item, ...project } : item)
  projectRecents.reconcile([project], [])
  if (isWorkspaceSection.value) void loadNavigationProjects()
}
onMounted(() => {
  window.addEventListener(PROJECT_LIFECYCLE_CHANGED, lifecycleChanged)
  window.addEventListener(PROJECT_SETTINGS_CHANGED, projectSettingsChanged)
})
onBeforeUnmount(() => {
  if (sidebarSearchTimer) clearTimeout(sidebarSearchTimer)
  window.removeEventListener(PROJECT_LIFECYCLE_CHANGED, lifecycleChanged)
  window.removeEventListener(PROJECT_SETTINGS_CHANGED, projectSettingsChanged)
})
</script>

<template>
  <div
    class="app-shell"
    :class="{
      'app-shell--context-open': contextNavigationOpen && hasContextNavigation,
      'app-shell--workspace': isWorkspaceSection,
      'app-shell--fixed': fixedFrame,
    }"
  >
    <aside
      class="module-rail"
      aria-label="功能模块"
    >
      <button
        class="module-rail__brand"
        type="button"
        aria-label="返回首页"
        @click="navigate('home')"
      >
        <img
          :src="brandLogo"
          width="40"
          height="40"
          alt="YumpooPlatform"
        >
      </button>
      <nav
        class="module-rail__items"
        data-tour="shell-modules"
        :class="{ 'module-rail__items--shifted': !contextNavigationOpen && hasContextNavigation }"
      >
        <button
          v-if="hasContextNavigation"
          class="module-rail__expand"
          type="button"
          data-tour="context-nav-expand"
          :aria-label="`展开${contextTitle}菜单`"
          aria-controls="desktop-context-navigation"
          :aria-expanded="contextNavigationOpen"
          :aria-hidden="contextNavigationOpen"
          :tabindex="contextNavigationOpen ? -1 : 0"
          @click="expandContextNavigation"
        >
          <svg
            viewBox="0 0 20 20"
            fill="none"
            aria-hidden="true"
          >
            <rect
              x="2.75"
              y="3.25"
              width="14.5"
              height="13.5"
              rx="1.75"
              stroke="currentColor"
              stroke-width="1.5"
            />
            <path
              d="M7 3.5v13M10 7l3 3-3 3"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </button>
        <el-tooltip
          v-for="item in moduleItems"
          :key="item.section"
          :content="item.label"
          placement="right"
        >
          <button
            class="module-rail__item"
            :class="{ active: activeSection === item.section }"
            type="button"
            :aria-label="item.label"
            :aria-current="activeSection === item.section ? 'page' : undefined"
            @click="navigate(item.routeName)"
          >
            <el-icon aria-hidden="true">
              <component :is="item.icon" />
            </el-icon>
            <span class="module-rail__item-label">{{ item.label }}</span>
            <operations-badge
              v-if="item.section === 'operations'"
            />
          </button>
        </el-tooltip>
      </nav>
    </aside>

    <header class="app-topbar">
      <div class="app-topbar__context">
        <div>
          <span class="app-topbar__label">YumpooPlatform</span>
          <strong>{{ session.authentication.value?.company.displayName }}</strong>
        </div>
      </div>
      <div class="app-topbar__actions">
        <div
          class="app-topbar__tools"
          role="group"
          aria-label="工具"
        >
          <el-tooltip
            :content="routeTour ? '查看本页引导' : '本页暂无引导'"
            placement="bottom"
            :show-after="400"
          >
            <span class="app-topbar__tool-wrap">
              <button
                type="button"
                class="app-topbar__tool"
                data-tour="topbar-tour"
                aria-label="查看本页引导"
                :disabled="!routeTour"
                @click="routeTour && onboarding.replay(routeTour)"
              >
                <shell-icon name="tour" />
              </button>
            </span>
          </el-tooltip>
          <el-dropdown
            trigger="click"
            placement="bottom-end"
            @command="openHelp"
            @visible-change="helpMenuOpen = $event"
          >
            <span class="app-topbar__tool-wrap">
              <el-tooltip
                content="帮助"
                placement="bottom"
                :show-after="400"
                :disabled="helpMenuOpen"
              >
                <button
                  type="button"
                  class="app-topbar__tool"
                  :class="{ 'is-active': helpMenuOpen }"
                  data-tour="topbar-help"
                  aria-label="帮助"
                >
                  <shell-icon name="help" />
                </button>
              </el-tooltip>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                  command="page"
                  :disabled="!routeHelp"
                >
                  本页帮助
                </el-dropdown-item>
                <el-dropdown-item command="/docs/start/first-day">
                  快速开始
                </el-dropdown-item>
                <el-dropdown-item command="/docs">
                  帮助中心
                </el-dropdown-item>
                <el-dropdown-item command="/docs/guide/faq">
                  常见问题
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <inbox-bell />
          <el-tooltip
            content="偏好设置"
            placement="bottom"
            :show-after="400"
          >
            <button
              type="button"
              class="app-topbar__tool"
              :class="{ 'is-active': route.name === 'settings' }"
              data-tour="topbar-preferences"
              aria-label="偏好设置"
              @click="router.push({ name: 'settings' })"
            >
              <shell-icon name="settings" />
            </button>
          </el-tooltip>
        </div>
        <span
          class="app-topbar__divider"
          aria-hidden="true"
        />
        <el-dropdown trigger="click">
          <el-button
            class="user-menu-trigger"
            data-tour="topbar-user"
          >
            <yp-assignee
              :user-id="session.authentication.value?.user.id"
              :display-name="session.authentication.value?.user.displayName"
              size="table"
            />
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item
                v-if="releaseLabel"
                disabled
              >
                {{ releaseLabel }}
              </el-dropdown-item>
              <el-dropdown-item
                :divided="!!releaseLabel"
                @click="router.push({ name: 'settings' })"
              >
                偏好设置
              </el-dropdown-item>
              <el-dropdown-item
                v-if="!inDesktopShell"
                @click="router.push({ name: 'desktop-download' })"
              >
                下载桌面客户端
              </el-dropdown-item>
              <el-dropdown-item
                divided
                :disabled="session.logoutLoading.value"
                @click="signOut"
              >
                退出并重新认证
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <aside
      v-if="hasContextNavigation"
      id="desktop-context-navigation"
      class="context-navigation"
      :aria-hidden="!contextNavigationOpen"
      :inert="!contextNavigationOpen"
    >
      <div
        v-if="isWorkspaceSection"
        class="context-navigation__header context-navigation__header--workspace"
      >
        <div
          v-if="!sidebarSearchOpen"
          class="workspace-navigation-heading"
        >
          <div class="workspace-navigation-heading__identity">
            <span
              class="workspace-navigation-heading__icon"
              aria-hidden="true"
            >
              <el-icon><folder-opened /></el-icon>
            </span>
            <strong>工作台</strong>
          </div>
          <div class="workspace-navigation-heading__actions">
            <button
              type="button"
              aria-label="搜索工作台项目"
              @click="openSidebarSearch"
            >
              <el-icon aria-hidden="true">
                <search />
              </el-icon>
            </button>
            <button
              type="button"
              aria-label="收起工作台菜单"
              aria-controls="desktop-context-navigation"
              :aria-expanded="contextNavigationOpen"
              @click="collapseContextNavigation"
            >
              <svg
                viewBox="0 0 20 20"
                fill="none"
                aria-hidden="true"
              >
                <rect
                  x="2.75"
                  y="3.25"
                  width="14.5"
                  height="13.5"
                  rx="1.75"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
                <path
                  d="M7 3.5v13M13 7l-3 3 3 3"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
          </div>
        </div>
        <div
          v-else
          class="workspace-navigation-search"
        >
          <el-icon aria-hidden="true">
            <search />
          </el-icon>
          <input
            ref="desktopSearchInput"
            v-model="sidebarSearchQuery"
            type="search"
            maxlength="80"
            aria-label="搜索项目名称或编码"
            placeholder="搜索项目"
            @input="scheduleSidebarSearch"
            @keydown.esc.stop.prevent="closeSidebarSearch"
          >
          <button
            type="button"
            aria-label="关闭项目搜索"
            @click="closeSidebarSearch"
          >
            <el-icon aria-hidden="true">
              <close />
            </el-icon>
          </button>
        </div>
      </div>
      <div
        v-else
        class="context-navigation__header context-navigation__header--section"
      >
        <div>
          <span>当前区域</span>
          <strong>{{ contextTitle }}</strong>
        </div>
        <button
          type="button"
          class="context-navigation__collapse"
          :aria-label="`收起${contextTitle}菜单`"
          aria-controls="desktop-context-navigation"
          :aria-expanded="contextNavigationOpen"
          @click="collapseContextNavigation"
        >
          <svg
            viewBox="0 0 20 20"
            fill="none"
            aria-hidden="true"
          >
            <rect
              x="2.75"
              y="3.25"
              width="14.5"
              height="13.5"
              rx="1.75"
              stroke="currentColor"
              stroke-width="1.5"
            />
            <path
              d="M7 3.5v13M13 7l-3 3 3 3"
              stroke="currentColor"
              stroke-width="1.5"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </button>
      </div>
      <nav
        class="global-navigation"
        aria-label="当前区域导航"
        data-tour="context-nav"
      >
        <template v-if="isWorkspaceSection">
          <button
            type="button"
            :class="{ active: route.name === 'dashboards' }"
            :aria-current="route.name === 'dashboards' ? 'page' : undefined"
            @click="navigate('dashboards')"
          >
            <el-icon aria-hidden="true">
              <grid />
            </el-icon><span>仪表板</span>
          </button>
          <button
            type="button"
            :class="{ active: myTimeActive }"
            :aria-current="myTimeActive ? 'page' : undefined"
            @click="navigate('my-time')"
          >
            <el-icon aria-hidden="true">
              <calendar />
            </el-icon><span>我的工时</span>
          </button>
          <div class="project-navigation__head">
            <button
              class="project-navigation__toggle"
              type="button"
              :aria-expanded="projectNavigationOpen"
              aria-controls="desktop-project-navigation"
              @click="projectNavigationOpen = !projectNavigationOpen"
            >
              <span>项目</span>
              <svg
                viewBox="0 0 20 20"
                fill="currentColor"
                width="12"
                height="12"
                aria-hidden="true"
                class="project-navigation__chevron"
              >
                <path d="M9.442 12.76a.77.77 0 0 0 1.116 0l4.21-4.363a.84.84 0 0 0 0-1.157.77.77 0 0 0-1.116 0L10 11.025 6.348 7.24a.77.77 0 0 0-1.117 0 .84.84 0 0 0 0 1.157l4.21 4.363Z" />
              </svg>
            </button>
            <button
              type="button"
              class="project-navigation__add"
              aria-label="创建项目"
              title="创建项目"
              @click="projectCreateOpen = true"
            >
              <svg
                viewBox="0 0 16 16"
                width="14"
                height="14"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                aria-hidden="true"
              ><path d="M8 3.25v9.5M3.25 8h9.5" /></svg>
            </button>
          </div>
          <div
            v-show="projectNavigationOpen"
            id="desktop-project-navigation"
            class="project-navigation__children"
          >
            <button
              type="button"
              :aria-current="route.name === 'workspace' ? 'page' : undefined"
              :class="{ active: route.name === 'workspace' }"
              @click="navigate('workspace')"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="16"
                height="16"
                fill="currentColor"
                viewBox="0 0 20 20"
                aria-hidden="true"
              >
                <path d="M14.688 9.267a1.1 1.1 0 0 1 1.03.707l.235.606.702.41.639-.1a1.1 1.1 0 0 1 1.123.545l.27.471a1.12 1.12 0 0 1-.092 1.251l-.404.512v.817l.403.51a1.12 1.12 0 0 1 .092 1.253l-.27.47a1.107 1.107 0 0 1-1.122.544l-.639-.098-.702.41-.235.606a1.1 1.1 0 0 1-1.03.707h-.54a1.1 1.1 0 0 1-1.029-.707l-.236-.607-.702-.409-.639.098a1.096 1.096 0 0 1-1.122-.544l-.272-.471a1.12 1.12 0 0 1 .093-1.25l.404-.512v-.816l-.403-.51a1.12 1.12 0 0 1-.093-1.254l.27-.47a1.109 1.109 0 0 1 1.123-.545l.639.098.702-.409.235-.606a1.1 1.1 0 0 1 1.03-.707h.54Zm-6.303 1.598a.75.75 0 0 1 .158.018l-.238.427a1.684 1.684 0 0 0 .132 1.836l.573.747v1.196l-.574.748a1.683 1.683 0 0 0-.132 1.833l.045.08H3a.75.75 0 0 1-.75-.75v-5.385a.75.75 0 0 1 .75-.75h5.385ZM3.75 16.25h3.885v-3.885H3.75v3.885Zm10.668-3.959c-.47 0-.92.188-1.253.524a1.793 1.793 0 0 0 .575 2.912 1.759 1.759 0 0 0 1.93-.387 1.792 1.792 0 0 0-.268-2.748 1.763 1.763 0 0 0-.984-.301ZM8.385 2.25a.75.75 0 0 1 .75.75v5.385a.75.75 0 0 1-.75.75H3a.75.75 0 0 1-.75-.75V3A.75.75 0 0 1 3 2.25h5.385Zm8.615 0a.75.75 0 0 1 .75.75v5.385a.75.75 0 0 1-.75.75h-.53l-.248-.657a1.622 1.622 0 0 0-.725-.843h.753V3.75h-3.885v3.885h.886a1.7 1.7 0 0 0-.147.09 1.627 1.627 0 0 0-.578.753l-.248.657h-.663a.75.75 0 0 1-.75-.75V3a.75.75 0 0 1 .75-.75H17ZM3.75 7.635h3.885V3.75H3.75v3.885Z" />
              </svg>
              <span>管理项目</span>
            </button>
            <button
              type="button"
              :class="{ active: route.name === 'archived-projects' }"
              :aria-current="route.name === 'archived-projects' ? 'page' : undefined"
              @click="navigate('archived-projects')"
            >
              <el-icon aria-hidden="true">
                <box />
              </el-icon><span>归档项目</span>
            </button>
            <transition-group
              tag="div"
              name="project-navigation"
              class="project-navigation__projects"
            >
              <div
                v-for="project in displayedNavigationProjects"
                :key="project.id"
                class="project-navigation__item"
              >
                <el-tooltip
                  :content="project.name"
                  placement="right"
                >
                  <button
                    class="project-navigation__project"
                    type="button"
                    :aria-current="route.params.projectId === project.id ? 'page' : undefined"
                    :class="{ active: route.params.projectId === project.id }"
                    @click="navigateProject(project)"
                  >
                    <svg
                      width="16"
                      height="16"
                      viewBox="0 0 20 20"
                      fill="none"
                      aria-hidden="true"
                    >
                      <path
                        d="M2.75 5.5c0-.966.784-1.75 1.75-1.75h3.19c.464 0 .91.184 1.238.513L10.165 5.5H15.5c.966 0 1.75.784 1.75 1.75v7.25a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-9Z"
                        stroke="currentColor"
                        stroke-width="1.5"
                        stroke-linejoin="round"
                      />
                    </svg>
                    <span class="project-navigation__project-name">{{ project.name }}</span>
                    <svg
                      v-if="pinnedProjectIds.has(project.id)"
                      class="project-navigation__pin"
                      width="12"
                      height="12"
                      viewBox="0 0 20 20"
                      fill="currentColor"
                      aria-hidden="true"
                    >
                      <path d="M7 2.75h6l-.9 4.05 2.4 2.4v1.3h-3.75v5.75L10 17.5l-.75-1.25V10.5H5.5V9.2l2.4-2.4L7 2.75Z" />
                    </svg>
                  </button>
                </el-tooltip>
              </div>
            </transition-group>
            <el-tooltip
              v-if="hasMoreNavigationProjects"
              content="查看全部项目"
              placement="right"
            >
              <button
                class="project-navigation__more"
                type="button"
                aria-label="查看全部项目"
                @click="navigate('workspace')"
              >
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                  aria-hidden="true"
                >
                  <circle
                    cx="4"
                    cy="10"
                    r="1.5"
                  />
                  <circle
                    cx="10"
                    cy="10"
                    r="1.5"
                  />
                  <circle
                    cx="16"
                    cy="10"
                    r="1.5"
                  />
                </svg>
              </button>
            </el-tooltip>
          </div>
          <div
            v-if="navigationProjectsFailed"
            class="project-navigation__status"
            role="status"
          >
            项目加载失败
          </div>
          <div
            v-else-if="sidebarSearchOpen && !navigationProjects.length"
            class="project-navigation__status"
            role="status"
          >
            未找到项目
          </div>
        </template>
        <template
          v-for="item in contextItems"
          :key="item.routeName + (item.query?.filter || '')"
        >
          <div
            v-if="item.groupLabel"
            v-show="!isWorkspaceSection || projectNavigationOpen"
            class="context-navigation__group-label"
          >
            {{ item.groupLabel }}
          </div>
          <button
            v-show="!isWorkspaceSection || projectNavigationOpen"
            type="button"
            :aria-current="isContextItemActive(item) ? 'page' : undefined"
            class="context-item"
            :class="{ active: isContextItemActive(item) }"
            @click="navigate(item.routeName, item.params, item.query)"
          >
            <el-icon
              v-if="item.icon"
              aria-hidden="true"
            >
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.label }}</span><small
              v-if="item.badge"
              class="context-item-badge"
            >{{ item.badge }}</small>
          </button>
        </template>
      </nav>
    </aside>

    <main
      class="app-main"
      :class="{ 'app-main--fixed': fixedFrame }"
    >
      <desktop-update-banner />
      <inline-problem
        v-if="session.actionProblem.value"
        :problem="session.actionProblem.value"
      />
      <router-view />
    </main>

    <el-drawer
      v-model="mobileNavigationOpen"
      class="mobile-navigation"
      title="Yumpoo 导航"
      direction="ltr"
      size="min(340px, 88vw)"
    >
      <nav
        class="mobile-module-navigation"
        aria-label="移动端功能模块"
      >
        <button
          v-for="item in moduleItems"
          :key="item.section"
          type="button"
          :class="{ active: activeSection === item.section }"
          @click="navigate(item.routeName)"
        >
          {{ item.label }}
          <operations-badge
            v-if="item.section === 'operations' && mobileNavigationOpen"
          />
        </button>
      </nav>
      <div
        v-if="isWorkspaceSection"
        class="context-navigation__header context-navigation__header--workspace context-navigation__header--mobile"
      >
        <div
          v-if="sidebarSearchOpen"
          class="workspace-navigation-search"
        >
          <el-icon aria-hidden="true">
            <search />
          </el-icon>
          <input
            ref="mobileSearchInput"
            v-model="sidebarSearchQuery"
            type="search"
            maxlength="80"
            aria-label="搜索项目"
            placeholder="搜索项目名称或编码"
            @input="scheduleSidebarSearch"
            @keydown.esc.stop.prevent="closeSidebarSearch"
          >
          <button
            type="button"
            aria-label="关闭项目搜索"
            @click="closeSidebarSearch"
          >
            <el-icon aria-hidden="true">
              <close />
            </el-icon>
          </button>
        </div>
        <div
          v-else
          class="workspace-navigation-heading"
        >
          <div class="workspace-navigation-heading__identity">
            <span
              class="workspace-navigation-heading__icon"
              aria-hidden="true"
            >
              <el-icon>
                <folder-opened />
              </el-icon>
            </span>
            <span class="workspace-navigation-heading__title">工作台</span>
          </div>
          <div class="workspace-navigation-heading__actions">
            <button
              type="button"
              aria-label="搜索项目"
              @click="openSidebarSearch"
            >
              <el-icon aria-hidden="true">
                <search />
              </el-icon>
            </button>
            <button
              type="button"
              aria-label="收起工作台菜单"
              @click="closeMobileNavigation"
            >
              <svg
                viewBox="0 0 20 20"
                fill="none"
                aria-hidden="true"
              >
                <rect
                  x="2.75"
                  y="3.25"
                  width="14.5"
                  height="13.5"
                  rx="1.75"
                  stroke="currentColor"
                  stroke-width="1.5"
                />
                <path
                  d="M7 3.5v13M13 7l-3 3 3 3"
                  stroke="currentColor"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
          </div>
        </div>
      </div>
      <div
        v-else
        class="mobile-context-title"
      >
        {{ contextTitle }}
      </div>
      <nav
        class="global-navigation mobile"
        aria-label="移动端当前区域导航"
      >
        <template v-if="isWorkspaceSection">
          <button
            type="button"
            :class="{ active: route.name === 'dashboards' }"
            :aria-current="route.name === 'dashboards' ? 'page' : undefined"
            @click="navigate('dashboards')"
          >
            <el-icon aria-hidden="true">
              <grid />
            </el-icon><span>仪表板</span>
          </button>
          <button
            type="button"
            :class="{ active: myTimeActive }"
            :aria-current="myTimeActive ? 'page' : undefined"
            @click="navigate('my-time')"
          >
            <el-icon aria-hidden="true">
              <calendar />
            </el-icon><span>我的工时</span>
          </button>
          <div class="project-navigation__head">
            <button
              type="button"
              class="project-navigation__toggle"
              :aria-expanded="projectNavigationOpen"
              @click="projectNavigationOpen = !projectNavigationOpen"
            >
              <span>项目</span>
              <svg
                viewBox="0 0 20 20"
                fill="currentColor"
                width="12"
                height="12"
                aria-hidden="true"
                class="project-navigation__chevron"
              ><path d="M9.442 12.76a.77.77 0 0 0 1.116 0l4.21-4.363a.84.84 0 0 0 0-1.157.77.77 0 0 0-1.116 0L10 11.025 6.348 7.24a.77.77 0 0 0-1.117 0 .84.84 0 0 0 0 1.157l4.21 4.363Z" /></svg>
            </button>
            <button
              type="button"
              class="project-navigation__add"
              aria-label="创建项目"
              title="创建项目"
              @click="mobileNavigationOpen = false; projectCreateOpen = true"
            >
              <svg
                viewBox="0 0 16 16"
                width="14"
                height="14"
                fill="none"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
                aria-hidden="true"
              ><path d="M8 3.25v9.5M3.25 8h9.5" /></svg>
            </button>
          </div>
          <div
            v-show="projectNavigationOpen"
            class="project-navigation__children"
          >
            <button
              type="button"
              :class="{ active: route.name === 'workspace' }"
              @click="navigate('workspace')"
            >
              <svg
                xmlns="http://www.w3.org/2000/svg"
                width="16"
                height="16"
                fill="currentColor"
                viewBox="0 0 20 20"
                aria-hidden="true"
              ><path d="M14.688 9.267a1.1 1.1 0 0 1 1.03.707l.235.606.702.41.639-.1a1.1 1.1 0 0 1 1.123.545l.27.471a1.12 1.12 0 0 1-.092 1.251l-.404.512v.817l.403.51a1.12 1.12 0 0 1 .092 1.253l-.27.47a1.107 1.107 0 0 1-1.122.544l-.639-.098-.702.41-.235.606a1.1 1.1 0 0 1-1.03.707h-.54a1.1 1.1 0 0 1-1.029-.707l-.236-.607-.702-.409-.639.098a1.096 1.096 0 0 1-1.122-.544l-.272-.471a1.12 1.12 0 0 1 .093-1.25l.404-.512v-.816l-.403-.51a1.12 1.12 0 0 1-.093-1.254l.27-.47a1.109 1.109 0 0 1 1.123-.545l.639.098.702-.409.235-.606a1.1 1.1 0 0 1 1.03-.707h.54Zm-6.303 1.598a.75.75 0 0 1 .158.018l-.238.427a1.684 1.684 0 0 0 .132 1.836l.573.747v1.196l-.574.748a1.683 1.683 0 0 0-.132 1.833l.045.08H3a.75.75 0 0 1-.75-.75v-5.385a.75.75 0 0 1 .75-.75h5.385ZM3.75 16.25h3.885v-3.885H3.75v3.885Zm10.668-3.959c-.47 0-.92.188-1.253.524a1.793 1.793 0 0 0 .575 2.912 1.759 1.759 0 0 0 1.93-.387 1.792 1.792 0 0 0-.268-2.748 1.763 1.763 0 0 0-.984-.301ZM8.385 2.25a.75.75 0 0 1 .75.75v5.385a.75.75 0 0 1-.75.75H3a.75.75 0 0 1-.75-.75V3A.75.75 0 0 1 3 2.25h5.385Zm8.615 0a.75.75 0 0 1 .75.75v5.385a.75.75 0 0 1-.75.75h-.53l-.248-.657a1.622 1.622 0 0 0-.725-.843h.753V3.75h-3.885v3.885h.886a1.7 1.7 0 0 0-.147.09 1.627 1.627 0 0 0-.578.753l-.248.657h-.663a.75.75 0 0 1-.75-.75V3a.75.75 0 0 1 .75-.75H17ZM3.75 7.635h3.885V3.75H3.75v3.885Z" /></svg>
              <span>管理项目</span>
            </button>
            <button
              type="button"
              :class="{ active: route.name === 'archived-projects' }"
              :aria-current="route.name === 'archived-projects' ? 'page' : undefined"
              @click="navigate('archived-projects')"
            >
              <el-icon aria-hidden="true">
                <box />
              </el-icon><span>归档项目</span>
            </button>
            <transition-group
              tag="div"
              name="project-navigation"
              class="project-navigation__projects"
            >
              <div
                v-for="project in displayedNavigationProjects"
                :key="project.id"
                class="project-navigation__item"
              >
                <el-tooltip
                  :content="project.name"
                  placement="top"
                >
                  <button
                    class="project-navigation__project"
                    type="button"
                    :class="{ active: route.params.projectId === project.id }"
                    @click="navigateProject(project)"
                  >
                    <svg
                      width="16"
                      height="16"
                      viewBox="0 0 20 20"
                      fill="none"
                      aria-hidden="true"
                    >
                      <path
                        d="M2.75 5.5c0-.966.784-1.75 1.75-1.75h3.19c.464 0 .91.184 1.238.513L10.165 5.5H15.5c.966 0 1.75.784 1.75 1.75v7.25a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-9Z"
                        stroke="currentColor"
                        stroke-width="1.5"
                        stroke-linejoin="round"
                      />
                    </svg>
                    <span class="project-navigation__project-name">{{ project.name }}</span>
                    <svg
                      v-if="pinnedProjectIds.has(project.id)"
                      class="project-navigation__pin"
                      width="12"
                      height="12"
                      viewBox="0 0 20 20"
                      fill="currentColor"
                      aria-hidden="true"
                    >
                      <path d="M7 2.75h6l-.9 4.05 2.4 2.4v1.3h-3.75v5.75L10 17.5l-.75-1.25V10.5H5.5V9.2l2.4-2.4L7 2.75Z" />
                    </svg>
                  </button>
                </el-tooltip>
              </div>
            </transition-group>
            <el-tooltip
              v-if="hasMoreNavigationProjects"
              content="查看全部项目"
              placement="top"
            >
              <button
                class="project-navigation__more"
                type="button"
                aria-label="查看全部项目"
                @click="navigate('workspace')"
              >
                <svg
                  width="16"
                  height="16"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                  aria-hidden="true"
                >
                  <circle
                    cx="4"
                    cy="10"
                    r="1.5"
                  />
                  <circle
                    cx="10"
                    cy="10"
                    r="1.5"
                  />
                  <circle
                    cx="16"
                    cy="10"
                    r="1.5"
                  />
                </svg>
              </button>
            </el-tooltip>
          </div>
          <div
            v-if="navigationProjectsFailed"
            class="project-navigation__status"
            role="status"
          >
            项目加载失败
          </div>
          <div
            v-else-if="sidebarSearchOpen && !navigationProjects.length"
            class="project-navigation__status"
            role="status"
          >
            未找到项目
          </div>
        </template>
        <template
          v-for="item in contextItems"
          :key="item.routeName + (item.query?.filter || '')"
        >
          <div
            v-if="item.groupLabel"
            v-show="!isWorkspaceSection || projectNavigationOpen"
            class="context-navigation__group-label"
          >
            {{ item.groupLabel }}
          </div>
          <button
            v-show="!isWorkspaceSection || projectNavigationOpen"
            type="button"
            class="context-item"
            :class="{ active: isContextItemActive(item) }"
            @click="navigate(item.routeName, item.params, item.query)"
          >
            <el-icon
              v-if="item.icon"
              aria-hidden="true"
            >
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.label }}</span><small
              v-if="item.badge"
              class="context-item-badge"
            >{{ item.badge }}</small>
          </button>
        </template>
      </nav>
    </el-drawer>

    <button
      class="mobile-nav-toggle"
      type="button"
      aria-label="打开导航"
      @click="mobileNavigationOpen = true"
    >
      <el-icon aria-hidden="true">
        <menu-icon />
      </el-icon>
    </button>
    <project-create-dialog
      v-model="projectCreateOpen"
      @created="projectCreated"
    />
    <onboarding-host />
  </div>
</template>

<style scoped>
.context-item {
  display: flex;
  align-items: center;
  gap: var(--yp-space-2);
}

.context-item-badge {
  min-width: 20px;
  height: 18px;
  margin-left: auto;
  padding: 0 6px;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-text-secondary);
  background: var(--yp-bg-sunken);
  font-family: var(--yp-font-family);
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.context-item.active .context-item-badge {
  color: var(--yp-status-blue-foreground);
  background: var(--yp-action-primary);
}
</style>
