<script setup lang="ts">
import { FolderOpened, Grid, Menu as MenuIcon, Setting, User } from '@element-plus/icons-vue'
import { AuthenticationClientType } from '@yumpoo/api-client'
import {
  ElButton,
  ElDrawer,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElIcon,
  ElTooltip,
} from 'element-plus'
import { computed, ref, type Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { beginAuthentication } from '../auth/navigation'
import { useAppearance } from '../composables/useAppearance'
import { useSession } from '../composables/useSession'
import type { ShellSection } from '../router/shell-navigation'
import InlineProblem from './InlineProblem.vue'
import YpAssignee from './yp/YpAssignee.vue'
import YpThemeSwitcher from './yp/YpThemeSwitcher.vue'

interface ModuleItem {
  section: ShellSection
  label: string
  routeName: 'home' | 'projects' | 'identity-overview'
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
const contextNavigationOpen = ref(typeof window === 'undefined'
  ? true
  : (window.matchMedia?.('(min-width: 1280px)').matches ?? true))

const clientLabel = computed(() => session.authentication.value?.client.type === AuthenticationClientType.Electron
  ? 'Electron 在线壳'
  : 'Web 浏览器')
const activeSection = computed<ShellSection>(() => route.meta.shellSection ?? 'work')
const moduleItems = computed<ModuleItem[]>(() => [
  { section: 'work', label: '工作台', routeName: 'home', icon: Grid },
  { section: 'projects', label: '项目', routeName: 'projects', icon: FolderOpened },
  ...(session.isIdentityReader.value
    ? [{ section: 'identity', label: '身份管理', routeName: 'identity-overview', icon: User } as const]
    : []),
])
const contextTitle = computed(() => ({
  work: '我的工作',
  projects: '项目',
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
  if (activeSection.value === 'projects') {
    const items: ContextItem[] = [{ label: '项目目录', routeName: 'projects', icon: FolderOpened }]
    const projectId = typeof route.params.projectId === 'string' ? route.params.projectId : undefined
    if (projectId) {
      items.push(
        { label: '概览', routeName: 'project-overview', params: { projectId }, icon: Grid, groupLabel: '当前项目' },
        { label: '成员', routeName: 'project-members', params: { projectId }, icon: User },
        { label: '设置', routeName: 'project-settings', params: { projectId }, icon: Setting },
      )
    }
    return items
  }
  return [{ label: '工作台', routeName: 'home' }]
})

function navigate(name: string, params?: Record<string, string>): void {
  mobileNavigationOpen.value = false
  void router.push(params ? { name, params } : { name })
}

function isContextItemActive(item: ContextItem): boolean {
  return route.name === item.routeName
}

async function signOut(): Promise<void> {
  const target = route.fullPath
  if (await session.logout()) beginAuthentication(target)
}
</script>

<template>
  <div
    class="app-shell"
    :class="{
      'app-shell--context-open': contextNavigationOpen,
      'app-shell--projects': activeSection === 'projects',
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
        @click="navigate('home')"
      >
        Y
      </button>
      <nav class="module-rail__items">
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
          </button>
        </el-tooltip>
      </nav>
    </aside>

    <header class="app-topbar">
      <div class="app-topbar__context">
        <el-button
          class="context-toggle"
          aria-label="切换上下文导航"
          :aria-expanded="contextNavigationOpen"
          @click="contextNavigationOpen = !contextNavigationOpen"
        >
          <el-icon aria-hidden="true">
            <menu-icon />
          </el-icon>
        </el-button>
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
      class="context-navigation"
      :aria-hidden="!contextNavigationOpen"
      :inert="!contextNavigationOpen"
    >
      <div class="context-navigation__header">
        <span>当前区域</span>
        <strong>{{ contextTitle }}</strong>
      </div>
      <nav
        class="global-navigation"
        aria-label="当前区域导航"
      >
        <template
          v-for="item in contextItems"
          :key="item.routeName"
        >
          <div
            v-if="item.groupLabel"
            class="context-navigation__group-label"
          >
            {{ item.groupLabel }}
          </div>
          <button
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
      <div class="mobile-context-title">
        {{ contextTitle }}
      </div>
      <nav
        class="global-navigation mobile"
        aria-label="移动端当前区域导航"
      >
        <template
          v-for="item in contextItems"
          :key="item.routeName"
        >
          <div
            v-if="item.groupLabel"
            class="context-navigation__group-label"
          >
            {{ item.groupLabel }}
          </div>
          <button
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
