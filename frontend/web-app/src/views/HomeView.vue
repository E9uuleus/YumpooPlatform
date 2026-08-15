<script setup lang="ts">
import type { DesktopAuthErrorCode, DesktopAuthStatus } from '@yumpoo/preload-contract'
import { ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElTag } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../composables/useSession'

const router = useRouter()
const session = useSession()
const authEnabled = ref(false)
const authStatus = ref<DesktopAuthStatus>({ phase: 'IDLE' })
let stopAuthStatus: (() => void) | undefined

const phaseLabels: Record<DesktopAuthStatus['phase'], string> = {
  IDLE: '尚未开始', OPENING_BROWSER: '正在打开系统浏览器', WAITING_FOR_CALLBACK: '等待企业微信回调',
  EXCHANGING: '正在安全兑换', SUCCEEDED: '验证成功', FAILED: '验证失败',
}
const errorLabels: Record<DesktopAuthErrorCode, string> = {
  AUTH_DISABLED: '当前验证门禁未启用', AUTH_IN_PROGRESS: '已有登录验证正在进行',
  BROWSER_OPEN_FAILED: '系统浏览器打开失败', INVALID_CALLBACK: '登录回调格式无效',
  NO_PENDING_ATTEMPT: '没有可匹配的登录验证', ATTEMPT_EXPIRED: '登录验证已过期',
  STATE_MISMATCH: '登录回调校验失败', EXCHANGE_FAILED: '登录交接兑换失败', IPC_REJECTED: '桌面安全桥不可用',
}
const authStatusLabel = computed(() => phaseLabels[authStatus.value.phase])
const authErrorLabel = computed(() => authStatus.value.errorCode ? errorLabels[authStatus.value.errorCode] : undefined)
const authInProgress = computed(() => ['OPENING_BROWSER', 'WAITING_FOR_CALLBACK', 'EXCHANGING'].includes(authStatus.value.phase))

async function startDesktopAuth(): Promise<void> {
  try { await window.yumpooDesktop?.auth.start() }
  catch { authStatus.value = { phase: 'FAILED', errorCode: 'IPC_REJECTED' } }
}

onMounted(async () => {
  const desktopAuth = window.yumpooDesktop?.auth
  if (!desktopAuth) return
  stopAuthStatus = desktopAuth.onStatus(status => { authStatus.value = status })
  try { authEnabled.value = await desktopAuth.isEnabled() }
  catch { authEnabled.value = false }
})
onBeforeUnmount(() => stopAuthStatus?.())
</script>

<template>
  <section class="home-page">
    <header class="welcome-banner">
      <div>
        <p class="eyebrow">
          工作台
        </p>
        <h2>欢迎回来，{{ session.authentication.value?.user.displayName }}</h2>
        <p>在这里进入已授权的 Yumpoo 平台功能。</p>
      </div>
      <el-button
        v-if="session.isIdentityReader.value"
        type="primary"
        @click="router.push({ name: 'identity-overview' })"
      >
        进入身份管理
      </el-button>
    </header>

    <div class="home-grid">
      <el-card shadow="never">
        <template #header>
          <strong>当前身份</strong>
        </template>
        <el-descriptions
          :column="1"
          border
        >
          <el-descriptions-item label="用户">
            {{ session.authentication.value?.user.displayName }}
          </el-descriptions-item>
          <el-descriptions-item label="公司">
            {{ session.authentication.value?.company.displayName }}
          </el-descriptions-item>
          <el-descriptions-item label="角色">
            <el-tag
              v-for="role in session.authentication.value?.roles"
              :key="role"
              class="role-tag"
              effect="plain"
            >
              {{ role }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
      <el-card shadow="never">
        <template #header>
          <strong>可用功能</strong>
        </template>
        <p v-if="session.isIdentityReader.value">
          你可以查看身份与组织状态、同步运行和成员账号。
        </p>
        <p v-else>
          你的登录状态有效；当前没有额外管理入口。
        </p>
      </el-card>
    </div>

    <el-alert
      v-if="authEnabled"
      class="desktop-auth-panel"
      :title="`Electron 登录交接验证：${authStatusLabel}${authErrorLabel ? `（${authErrorLabel}）` : ''}`"
      type="info"
      :closable="false"
      show-icon
    >
      <template #default>
        <el-button
          type="primary"
          :loading="authInProgress"
          :disabled="authInProgress"
          @click="startDesktopAuth"
        >
          使用系统浏览器验证
        </el-button>
      </template>
    </el-alert>
  </section>
</template>
