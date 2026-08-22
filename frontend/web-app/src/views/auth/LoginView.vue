<script setup lang="ts">
import type { DesktopAuthErrorCode, DesktopAuthStatus } from '@yumpoo/preload-contract'
import { ElAlert, ElButton, ElCard } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  beginAuthentication,
  consumeReturnPath,
  resetAuthenticationNavigation,
} from '../../auth/navigation'
import YpThemeSwitcher from '../../components/yp/YpThemeSwitcher.vue'
import { useAppearance } from '../../composables/useAppearance'
import { ensureAuthentication } from '../../composables/useSession'

const route = useRoute()
const router = useRouter()
const appearance = useAppearance()
const isDesktop = Boolean(window.yumpooDesktop)
const desktopEnabled = ref(false)
const status = ref<DesktopAuthStatus>({ phase: 'IDLE' })
let stopStatus: (() => void) | undefined

const inProgress = computed(() => [
  'OPENING_BROWSER', 'WAITING_FOR_CALLBACK', 'EXCHANGING',
].includes(status.value.phase))
const callbackMessage = computed(() => {
  if (route.query.reason === 'unavailable') return '企业微信服务暂时不可用，请稍后重试。'
  if (route.query.reason === 'authentication') return '登录未完成或账号无权访问，请重新扫码。'
  return undefined
})
const errorLabels: Record<DesktopAuthErrorCode, string> = {
  AUTH_DISABLED: '当前桌面登录未启用。',
  AUTH_IN_PROGRESS: '登录正在进行，请在浏览器完成扫码。',
  BROWSER_OPEN_FAILED: '无法打开系统浏览器。',
  INVALID_CALLBACK: '登录回调格式无效。',
  NO_PENDING_ATTEMPT: '登录请求不存在，请重新开始。',
  ATTEMPT_EXPIRED: '登录请求已过期。',
  STATE_MISMATCH: '登录回调校验失败。',
  EXCHANGE_FAILED: '桌面会话兑换失败。',
  IPC_REJECTED: '桌面安全桥不可用。',
}
const desktopMessage = computed(() => status.value.errorCode
  ? errorLabels[status.value.errorCode]
  : status.value.phase === 'WAITING_FOR_CALLBACK'
    ? '请在系统浏览器中使用企业微信扫码。'
    : undefined)
const visibleMessage = computed(() => callbackMessage.value ?? desktopMessage.value ?? '')

async function login(): Promise<void> {
  if (!isDesktop) {
    beginAuthentication()
    return
  }
  try {
    await window.yumpooDesktop?.auth.start()
  } catch {
    status.value = { phase: 'FAILED', errorCode: 'IPC_REJECTED' }
  }
}

onMounted(async () => {
  resetAuthenticationNavigation()
  const auth = window.yumpooDesktop?.auth
  if (!auth) return
  stopStatus = auth.onStatus(async (next) => {
    status.value = next
    if (next.phase === 'SUCCEEDED') {
      await ensureAuthentication(true)
      await router.replace(consumeReturnPath())
    }
  })
  desktopEnabled.value = await auth.isEnabled().catch(() => false)
})
onBeforeUnmount(() => stopStatus?.())
</script>

<template>
  <main class="login-page">
    <section
      class="login-brand"
      aria-label="YumpooPlatform 产品介绍"
    >
      <div class="login-brand__content">
        <div class="login-brand__mark">
          Y
        </div>
        <h1>让协作状态<br>一目了然</h1>
        <p>项目、成员与企业协作，都在一个工作空间。</p>
      </div>
    </section>
    <section class="login-workspace">
      <div class="login-appearance">
        <yp-theme-switcher
          :theme="appearance.themeMode.value"
          :density="appearance.densityMode.value"
          @update:theme="appearance.setThemeMode"
          @update:density="appearance.setDensityMode"
        />
      </div>
      <el-card
        class="login-card"
        shadow="never"
      >
        <p class="muted-text">
          YUMPOO PLATFORM
        </p>
        <h2>企业微信扫码登录</h2>
        <p class="description">
          {{ isDesktop ? '登录将在系统默认浏览器中完成。' : '点击后打开企业微信官方扫码页面。' }}
        </p>
        <el-alert
          v-if="visibleMessage"
          class="login-alert"
          :title="visibleMessage"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-button
          class="login-action"
          type="primary"
          size="large"
          :loading="inProgress"
          :disabled="inProgress || (isDesktop && !desktopEnabled)"
          @click="login"
        >
          {{ isDesktop ? '在系统浏览器中登录' : '使用企业微信扫码登录' }}
        </el-button>
        <p
          v-if="isDesktop && !desktopEnabled"
          class="hint"
        >
          当前桌面环境未启用安全登录。
        </p>
      </el-card>
    </section>
  </main>
</template>
