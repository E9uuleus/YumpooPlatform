<script setup lang="ts">
import { Close, FolderOpened, Grid, Menu as MenuIcon, Search, Setting, User } from '@element-plus/icons-vue'
import { AuthenticationClientType, ProjectLifecycleFilter, type ProjectSummary } from '@yumpoo/api-client'
import {
  ElButton,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElIcon,
  ElTooltip,
} from 'element-plus'
import { computed, nextTick, onBeforeUnmount, ref, watch, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { projectsApi } from '../api/client'
import { beginAuthentication } from '../auth/navigation'
import { useAppearance } from '../composables/useAppearance'
import { useProjectRecents } from '../composables/useProjectRecents'
import { useSession } from '../composables/useSession'
import type { ShellSection } from '../router/shell-navigation'
import InlineProblem from './InlineProblem.vue'
import YpAssignee from './yp/YpAssignee.vue'
import YpThemeSwitcher from './yp/YpThemeSwitcher.vue'

interface ModuleItem {
  section: ShellSection
  label: string
  routeName: 'workspace' | 'identity-overview'
  icon: Component
}

interface ContextItem {
  label: string
  routeName: string
  params?: Record<string, string>
  icon?: Component
  groupLabel?: string
}

const route = useRoute()
const router = useRouter()
const session = useSession()
const appearance = useAppearance()
const mobileNavigationOpen = ref(false)
const projectNavigationOpen = ref(true)
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

const clientLabel = computed(() => session.authentication.value?.client.type === AuthenticationClientType.Electron
  ? 'Electron 在线壳'
  : 'Web 浏览器')
const activeSection = computed<ShellSection>(() => route.meta.shellSection ?? 'work')
const isWorkspaceSection = computed(() => activeSection.value === 'work')
const projectRecentScope = computed(() => {
  const authentication = session.authentication.value
  return authentication ? `${authentication.company.id}:${authentication.user.id}` : undefined
})
const projectRecents = useProjectRecents(() => projectRecentScope.value)
const moduleItems = computed<ModuleItem[]>(() => [
  { section: 'work', label: '工作台', routeName: 'workspace', icon: Grid },
  ...(session.isIdentityReader.value
    ? [{ section: 'identity', label: '身份管理', routeName: 'identity-overview', icon: User } as const]
    : []),
])
const contextTitle = computed(() => ({
  work: '工作台',
  identity: '身份与组织',
})[activeSection.value])
const contextItems = computed<ContextItem[]>(() => {
  if (activeSection.value === 'identity') {
    return [
      { label: '概览', routeName: 'identity-overview' },
      { label: '同步运行', routeName: 'identity-sync-runs' },
      { label: '成员管理', routeName: 'identity-members' },
    ]
  }
  if (isWorkspaceSection.value) {
    const items: ContextItem[] = []
    const projectId = typeof route.params.projectId === 'string' ? route.params.projectId : undefined
    if (projectId) {
      items.push(
        { label: '工作项', routeName: 'project-overview', params: { projectId }, icon: Grid, groupLabel: '当前项目' },
        { label: '成员', routeName: 'project-members', params: { projectId }, icon: User },
        { label: '设置', routeName: 'project-settings', params: { projectId }, icon: Setting },
      )
    }
    return items
  }
  return []
})

function navigate(name: string, params?: Record<string, string>): void {
  mobileNavigationOpen.value = false
  if (name === 'workspace') {
    const workspaceSlug = session.authentication.value?.user.workspaceSlug
    if (workspaceSlug) void router.push({ name: 'workspace', params: { workspaceSlug } })
    return
  }
  void router.push(params ? { name, params } : { name })
}

function navigateProject(project: ProjectSummary): void {
  resetSidebarSearch()
  projectRecents.record(project)
  navigate('project-overview', { projectId: project.id })
}

function isContextItemActive(item: ContextItem): boolean {
  return route.name === item.routeName
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
  try {
    const projectPage = await projectsApi.listProjects({
      ...(query ? { query } : {}),
      lifecycle: ProjectLifecycleFilter.All,
      page: 0,
      size: 11,
    })
    if (request !== navigationRequest) return
    navigationProjects.value = projectPage.items.slice(0, 10)
    hasMoreNavigationProjects.value = projectPage.totalElements > 10
    if (!query) {
      const activeProjectId = typeof route.params.projectId === 'string' ? route.params.projectId : undefined
      const activeProject = projectPage.items.find(project => project.id === activeProjectId)
      if (activeProject) projectRecents.record(activeProject)
    }
  } catch {
    if (request !== navigationRequest) return
    navigationProjects.value = []
    hasMoreNavigationProjects.value = false
    navigationProjectsFailed.value = true
  }
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

onBeforeUnmount(() => {
  if (sidebarSearchTimer) clearTimeout(sidebarSearchTimer)
})
</script>

<template>
  <div
    class="app-shell"
    :class="{
      'app-shell--context-open': contextNavigationOpen,
      'app-shell--workspace': isWorkspaceSection,
    }"
  >
    <aside
      class="module-rail"
      aria-label="功能模块"
    >
      <button
        class="module-rail__brand"
        type="button"
        aria-label="返回工作台"
        @click="navigate('workspace')"
      >
        Y
      </button>
      <nav
        class="module-rail__items"
        :class="{ 'module-rail__items--shifted': !contextNavigationOpen }"
      >
        <button
          class="module-rail__expand"
          type="button"
          aria-label="展开工作台菜单"
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
            <rect x="2.75" y="3.25" width="14.5" height="13.5" rx="1.75" stroke="currentColor" stroke-width="1.5" />
            <path d="M7 3.5v13M10 7l3 3-3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
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
        <span class="client-badge">{{ clientLabel }}</span>
        <yp-theme-switcher
          :theme="appearance.themeMode.value"
          :density="appearance.densityMode.value"
          @update:theme="appearance.setThemeMode"
          @update:density="appearance.setDensityMode"
        />
        <el-dropdown trigger="click">
          <el-button class="user-menu-trigger">
            <yp-assignee
              :user-id="session.authentication.value?.user.id"
              :display-name="session.authentication.value?.user.displayName"
              size="table"
            />
          </el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>
                {{ clientLabel }}
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
              <el-icon aria-hidden="true"><search /></el-icon>
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
                <rect x="2.75" y="3.25" width="14.5" height="13.5" rx="1.75" stroke="currentColor" stroke-width="1.5" />
                <path d="M7 3.5v13M13 7l-3 3 3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
          </div>
        </div>
        <div
          v-else
          class="workspace-navigation-search"
        >
          <el-icon aria-hidden="true"><search /></el-icon>
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
            <el-icon aria-hidden="true"><close /></el-icon>
          </button>
        </div>
      </div>
      <div
        v-else
        class="context-navigation__header"
      >
        <span>当前区域</span>
        <strong>{{ contextTitle }}</strong>
      </div>
      <nav
        class="global-navigation"
        aria-label="当前区域导航"
      >
        <template v-if="isWorkspaceSection">
          <button
            class="project-navigation__toggle"
            type="button"
            :aria-expanded="projectNavigationOpen"
            aria-controls="desktop-project-navigation"
            @click="projectNavigationOpen = !projectNavigationOpen"
          >
            <span>项目</span>
            <svg viewBox="0 0 20 20" fill="currentColor" width="12" height="12" aria-hidden="true" class="project-navigation__chevron">
              <path d="M9.442 12.76a.77.77 0 0 0 1.116 0l4.21-4.363a.84.84 0 0 0 0-1.157.77.77 0 0 0-1.116 0L10 11.025 6.348 7.24a.77.77 0 0 0-1.117 0 .84.84 0 0 0 0 1.157l4.21 4.363Z" />
            </svg>
          </button>
          <div v-show="projectNavigationOpen" id="desktop-project-navigation" class="project-navigation__children">
            <button
              type="button"
              :aria-current="route.name === 'workspace' ? 'page' : undefined"
              :class="{ active: route.name === 'workspace' }"
              @click="navigate('workspace')"
            >
              <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" viewBox="0 0 20 20" aria-hidden="true">
                <path d="M14.688 9.267a1.1 1.1 0 0 1 1.03.707l.235.606.702.41.639-.1a1.1 1.1 0 0 1 1.123.545l.27.471a1.12 1.12 0 0 1-.092 1.251l-.404.512v.817l.403.51a1.12 1.12 0 0 1 .092 1.253l-.27.47a1.107 1.107 0 0 1-1.122.544l-.639-.098-.702.41-.235.606a1.1 1.1 0 0 1-1.03.707h-.54a1.1 1.1 0 0 1-1.029-.707l-.236-.607-.702-.409-.639.098a1.096 1.096 0 0 1-1.122-.544l-.272-.471a1.12 1.12 0 0 1 .093-1.25l.404-.512v-.816l-.403-.51a1.12 1.12 0 0 1-.093-1.254l.27-.47a1.109 1.109 0 0 1 1.123-.545l.639.098.702-.409.235-.606a1.1 1.1 0 0 1 1.03-.707h.54Zm-6.303 1.598a.75.75 0 0 1 .158.018l-.238.427a1.684 1.684 0 0 0 .132 1.836l.573.747v1.196l-.574.748a1.683 1.683 0 0 0-.132 1.833l.045.08H3a.75.75 0 0 1-.75-.75v-5.385a.75.75 0 0 1 .75-.75h5.385ZM3.75 16.25h3.885v-3.885H3.75v3.885Zm10.668-3.959c-.47 0-.92.188-1.253.524a1.793 1.793 0 0 0 .575 2.912 1.759 1.759 0 0 0 1.93-.387 1.792 1.792 0 0 0-.268-2.748 1.763 1.763 0 0 0-.984-.301ZM8.385 2.25a.75.75 0 0 1 .75.75v5.385a.75.75 0 0 1-.75.75H3a.75.75 0 0 1-.75-.75V3A.75.75 0 0 1 3 2.25h5.385Zm8.615 0a.75.75 0 0 1 .75.75v5.385a.75.75 0 0 1-.75.75h-.53l-.248-.657a1.622 1.622 0 0 0-.725-.843h.753V3.75h-3.885v3.885h.886a1.7 1.7 0 0 0-.147.09 1.627 1.627 0 0 0-.578.753l-.248.657h-.663a.75.75 0 0 1-.75-.75V3a.75.75 0 0 1 .75-.75H17ZM3.75 7.635h3.885V3.75H3.75v3.885Z" />
              </svg>
              <span>管理项目</span>
            </button>
            <el-tooltip
              v-for="project in navigationProjects"
              :key="project.id"
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
                <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path d="M2.75 5.5c0-.966.784-1.75 1.75-1.75h3.19c.464 0 .91.184 1.238.513L10.165 5.5H15.5c.966 0 1.75.784 1.75 1.75v7.25a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-9Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
                </svg>
                <span class="project-navigation__project-name">{{ project.name }}</span>
              </button>
            </el-tooltip>
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
                <svg width="16" height="16" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <circle cx="4" cy="10" r="1.5" />
                  <circle cx="10" cy="10" r="1.5" />
                  <circle cx="16" cy="10" r="1.5" />
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
          :key="item.routeName"
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
            :class="{ active: isContextItemActive(item) }"
            @click="navigate(item.routeName, item.params)"
          >
            <el-icon
              v-if="item.icon"
              aria-hidden="true"
            >
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.label }}</span>
          </button>
        </template>
      </nav>
    </aside>

    <main class="app-main">
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
                <rect x="2.75" y="3.25" width="14.5" height="13.5" rx="1.75" stroke="currentColor" stroke-width="1.5" />
                <path d="M7 3.5v13M13 7l-3 3 3 3" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" />
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
          <button type="button" class="project-navigation__toggle" :aria-expanded="projectNavigationOpen" @click="projectNavigationOpen = !projectNavigationOpen">
            <span>项目</span>
            <svg viewBox="0 0 20 20" fill="currentColor" width="12" height="12" aria-hidden="true" class="project-navigation__chevron"><path d="M9.442 12.76a.77.77 0 0 0 1.116 0l4.21-4.363a.84.84 0 0 0 0-1.157.77.77 0 0 0-1.116 0L10 11.025 6.348 7.24a.77.77 0 0 0-1.117 0 .84.84 0 0 0 0 1.157l4.21 4.363Z" /></svg>
          </button>
          <div v-show="projectNavigationOpen" class="project-navigation__children">
            <button type="button" :class="{ active: route.name === 'workspace' }" @click="navigate('workspace')">
              <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" fill="currentColor" viewBox="0 0 20 20" aria-hidden="true"><path d="M14.688 9.267a1.1 1.1 0 0 1 1.03.707l.235.606.702.41.639-.1a1.1 1.1 0 0 1 1.123.545l.27.471a1.12 1.12 0 0 1-.092 1.251l-.404.512v.817l.403.51a1.12 1.12 0 0 1 .092 1.253l-.27.47a1.107 1.107 0 0 1-1.122.544l-.639-.098-.702.41-.235.606a1.1 1.1 0 0 1-1.03.707h-.54a1.1 1.1 0 0 1-1.029-.707l-.236-.607-.702-.409-.639.098a1.096 1.096 0 0 1-1.122-.544l-.272-.471a1.12 1.12 0 0 1 .093-1.25l.404-.512v-.816l-.403-.51a1.12 1.12 0 0 1-.093-1.254l.27-.47a1.109 1.109 0 0 1 1.123-.545l.639.098.702-.409.235-.606a1.1 1.1 0 0 1 1.03-.707h.54Zm-6.303 1.598a.75.75 0 0 1 .158.018l-.238.427a1.684 1.684 0 0 0 .132 1.836l.573.747v1.196l-.574.748a1.683 1.683 0 0 0-.132 1.833l.045.08H3a.75.75 0 0 1-.75-.75v-5.385a.75.75 0 0 1 .75-.75h5.385ZM3.75 16.25h3.885v-3.885H3.75v3.885Zm10.668-3.959c-.47 0-.92.188-1.253.524a1.793 1.793 0 0 0 .575 2.912 1.759 1.759 0 0 0 1.93-.387 1.792 1.792 0 0 0-.268-2.748 1.763 1.763 0 0 0-.984-.301ZM8.385 2.25a.75.75 0 0 1 .75.75v5.385a.75.75 0 0 1-.75.75H3a.75.75 0 0 1-.75-.75V3A.75.75 0 0 1 3 2.25h5.385Zm8.615 0a.75.75 0 0 1 .75.75v5.385a.75.75 0 0 1-.75.75h-.53l-.248-.657a1.622 1.622 0 0 0-.725-.843h.753V3.75h-3.885v3.885h.886a1.7 1.7 0 0 0-.147.09 1.627 1.627 0 0 0-.578.753l-.248.657h-.663a.75.75 0 0 1-.75-.75V3a.75.75 0 0 1 .75-.75H17ZM3.75 7.635h3.885V3.75H3.75v3.885Z" /></svg>
              <span>管理项目</span>
            </button>
            <el-tooltip
              v-for="project in navigationProjects"
              :key="project.id"
              :content="project.name"
              placement="top"
            >
              <button
                class="project-navigation__project"
                type="button"
                :class="{ active: route.params.projectId === project.id }"
                @click="navigateProject(project)"
              >
                <svg width="16" height="16" viewBox="0 0 20 20" fill="none" aria-hidden="true">
                  <path d="M2.75 5.5c0-.966.784-1.75 1.75-1.75h3.19c.464 0 .91.184 1.238.513L10.165 5.5H15.5c.966 0 1.75.784 1.75 1.75v7.25a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-9Z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
                </svg>
                <span class="project-navigation__project-name">{{ project.name }}</span>
              </button>
            </el-tooltip>
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
                <svg width="16" height="16" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <circle cx="4" cy="10" r="1.5" />
                  <circle cx="10" cy="10" r="1.5" />
                  <circle cx="16" cy="10" r="1.5" />
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
          :key="item.routeName"
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
            :class="{ active: isContextItemActive(item) }"
            @click="navigate(item.routeName, item.params)"
          >
            <el-icon
              v-if="item.icon"
              aria-hidden="true"
            >
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.label }}</span>
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
  </div>
</template>
