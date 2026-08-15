<script setup lang="ts">
import { AuthenticationClientType } from '@yumpoo/api-client'
import {
  ElAside,
  ElButton,
  ElContainer,
  ElDrawer,
  ElHeader,
  ElMain,
  ElTag,
} from 'element-plus'
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { beginAuthentication } from '../auth/navigation'
import { useSession } from '../composables/useSession'
import InlineProblem from './InlineProblem.vue'

const route = useRoute()
const router = useRouter()
const session = useSession()
const mobileNavigationOpen = ref(false)

const clientLabel = computed(() => session.authentication.value?.client.type === AuthenticationClientType.Electron
  ? 'Electron 在线壳'
  : 'Web 浏览器')
const activeSection = computed(() => route.path.startsWith('/admin/identity')
  ? 'identity'
  : 'home')

function navigate(name: 'home' | 'identity-overview'): void {
  mobileNavigationOpen.value = false
  void router.push({ name })
}

async function signOut(): Promise<void> {
  const target = route.fullPath
  if (await session.logout()) {
    beginAuthentication(target)
  }
}
</script>

<template>
  <el-container class="app-shell">
    <el-header class="app-header">
      <div class="brand-block">
        <el-button
          class="mobile-nav-toggle"
          plain
          aria-label="打开导航"
          @click="mobileNavigationOpen = true"
        >
          菜单
        </el-button>
        <div>
          <p class="app-kicker">
            YUMPOO PLATFORM
          </p>
          <h1>{{ session.authentication.value?.company.displayName }}</h1>
        </div>
      </div>
      <div class="header-actions">
        <el-tag effect="plain">
          {{ clientLabel }}
        </el-tag>
        <span class="current-user">{{ session.authentication.value?.user.displayName }}</span>
        <el-button
          :loading="session.logoutLoading.value"
          @click="signOut"
        >
          退出并重新认证
        </el-button>
      </div>
    </el-header>

    <el-container class="app-body">
      <el-aside
        class="app-aside"
        width="224px"
      >
        <nav
          class="global-navigation"
          aria-label="全局功能"
        >
          <button
            type="button"
            :aria-current="activeSection === 'home' ? 'page' : undefined"
            :class="{ active: activeSection === 'home' }"
            @click="navigate('home')"
          >
            首页
          </button>
          <button
            v-if="session.isIdentityReader.value"
            type="button"
            :aria-current="activeSection === 'identity' ? 'page' : undefined"
            :class="{ active: activeSection === 'identity' }"
            @click="navigate('identity-overview')"
          >
            身份管理
          </button>
        </nav>
      </el-aside>
      <el-main class="app-main">
        <inline-problem
          v-if="session.actionProblem.value"
          :problem="session.actionProblem.value"
        />
        <router-view />
      </el-main>
    </el-container>

    <el-drawer
      v-model="mobileNavigationOpen"
      title="Yumpoo 导航"
      direction="ltr"
      size="280px"
    >
      <nav
        class="global-navigation mobile"
        aria-label="移动端全局功能"
      >
        <button
          type="button"
          :aria-current="activeSection === 'home' ? 'page' : undefined"
          :class="{ active: activeSection === 'home' }"
          @click="navigate('home')"
        >
          首页
        </button>
        <button
          v-if="session.isIdentityReader.value"
          type="button"
          :aria-current="activeSection === 'identity' ? 'page' : undefined"
          :class="{ active: activeSection === 'identity' }"
          @click="navigate('identity-overview')"
        >
          身份管理
        </button>
      </nav>
    </el-drawer>
  </el-container>
</template>
