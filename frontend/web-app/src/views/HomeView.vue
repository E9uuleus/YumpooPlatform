<script setup lang="ts">
import type {
  DesktopAuthErrorCode,
  DesktopAuthStatus,
} from '@yumpoo/preload-contract'
import { ElAlert, ElButton, ElCard, ElTag } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const authEnabled = ref(false)
const authStatus = ref<DesktopAuthStatus>({ phase: 'IDLE' })
let stopAuthStatus: (() => void) | undefined

const phaseLabels: Record<DesktopAuthStatus['phase'], string> = {
  IDLE: '尚未开始',
  OPENING_BROWSER: '正在打开系统浏览器',
  WAITING_FOR_CALLBACK: '等待企业微信回调',
  EXCHANGING: '正在安全兑换',
  SUCCEEDED: '验证成功',
  FAILED: '验证失败',
}

const errorLabels: Record<DesktopAuthErrorCode, string> = {
  AUTH_DISABLED: '当前验证门禁未启用',
  AUTH_IN_PROGRESS: '已有登录验证正在进行',
  BROWSER_OPEN_FAILED: '系统浏览器打开失败',
  INVALID_CALLBACK: '登录回调格式无效',
  NO_PENDING_ATTEMPT: '没有可匹配的登录验证',
  ATTEMPT_EXPIRED: '登录验证已过期',
  STATE_MISMATCH: '登录回调校验失败',
  EXCHANGE_FAILED: '登录交接兑换失败',
  IPC_REJECTED: '桌面安全桥不可用',
}

const authStatusLabel = computed(() => phaseLabels[authStatus.value.phase])
const authErrorLabel = computed(() =>
  authStatus.value.errorCode
    ? errorLabels[authStatus.value.errorCode]
    : undefined,
)
const authInProgress = computed(() =>
  ['OPENING_BROWSER', 'WAITING_FOR_CALLBACK', 'EXCHANGING'].includes(
    authStatus.value.phase,
  ),
)

async function startDesktopAuth(): Promise<void> {
  try {
    await window.yumpooDesktop?.auth.start()
  } catch {
    authStatus.value = { phase: 'FAILED', errorCode: 'IPC_REJECTED' }
  }
}

onMounted(async () => {
  const desktopAuth = window.yumpooDesktop?.auth
  if (!desktopAuth) {
    return
  }
  stopAuthStatus = desktopAuth.onStatus((status) => {
    authStatus.value = status
  })
  try {
    authEnabled.value = await desktopAuth.isEnabled()
  } catch {
    authEnabled.value = false
  }
})

onBeforeUnmount(() => stopAuthStatus?.())
</script>

<template>
  <el-card
    class="skeleton-card"
    shadow="never"
  >
    <template #header>
      <div class="card-heading">
        <span>Vue SPA 已就绪</span>
        <el-tag size="small">
          M0-07B
        </el-tag>
      </div>
    </template>
    <p>
      当前仅提供可构建、可测试的在线应用壳。业务页面、身份接入、数据访问与 API
      契约将在后续里程碑实现。
    </p>
    <el-alert
      title="模块边界已启用"
      description="Web 运行时不会直接访问 Node、Electron 或桌面实现。"
      type="info"
      :closable="false"
      show-icon
    />
    <section
      v-if="authEnabled"
      class="desktop-auth-panel"
      aria-label="Electron 登录交接验证"
    >
      <div>
        <strong>Electron 登录交接验证</strong>
        <p data-testid="desktop-auth-status">
          {{ authStatusLabel }}
          <span v-if="authErrorLabel">：{{ authErrorLabel }}</span>
        </p>
      </div>
      <el-button
        type="primary"
        :loading="authInProgress"
        :disabled="authInProgress"
        @click="startDesktopAuth"
      >
        使用系统浏览器验证
      </el-button>
    </section>
  </el-card>
</template>
