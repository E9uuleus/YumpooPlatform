<script setup lang="ts">
import { DataAnalysis, FolderOpened, Message, Timer } from '@element-plus/icons-vue'
import { ElButton, ElDialog, ElIcon, ElTour, ElTourStep } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../composables/useSession'
import { TOURS, type TourStep } from './tours'
import { useOnboarding } from './useOnboarding'

const session = useSession()
const onboarding = useOnboarding()
const router = useRouter()
const steps = ref<TourStep[]>([])
const current = ref(0)
const open = computed({
  get: () => steps.value.length > 0,
  set: value => {
    const id = onboarding.active.value
    if (!value && id) onboarding.complete(id)
  },
})
const highlights: { label: string; text: string; icon: Component; tone: string }[] = [
  { label: '项目协作', text: '在表格或看板中管理工作项，讨论和附件集中在一处。', icon: FolderOpened, tone: 'blue' },
  { label: '计时与工时', text: '一键开始计时，工时自动汇总到日历。', icon: Timer, tone: 'green' },
  { label: '仪表板', text: '用图表和表格统计你的项目与工时。', icon: DataAnalysis, tone: 'purple' },
  { label: '收件箱', text: '@提及、评论和指派提醒都汇集到这里。', icon: Message, tone: 'red' },
]

// 当前页面不可见的目标（移动端、折叠的侧栏、看板视图、空表格）直接跳过。
function visible(selector: string): boolean {
  const element = document.querySelector(selector)
  return Boolean(element && element.getClientRects().length && !element.closest('[inert]'))
}

async function refreshSteps(): Promise<void> {
  const id = onboarding.active.value
  if (!id) return
  await nextTick()
  if (onboarding.active.value !== id) return
  const currentTitle = steps.value[current.value]?.title
  const available = TOURS[id].steps
    .filter(step => !step.target || visible(step.target))
    .map(step => {
      if (window.innerWidth >= 1100) return step
      const target = step.target ? document.querySelector(step.target) : null
      return {
        ...step,
        ...(/^(left|right)/.test(step.placement ?? '') ? { placement: 'bottom' as const } : {}),
        ...(target && target.getBoundingClientRect().height > window.innerHeight * 0.6 ? {
          showArrow: false,
          contentStyle: {
            position: 'fixed',
            top: '50%',
            left: '50%',
            transform: 'translate3d(-50%, -50%, 0)',
            maxWidth: 'calc(100vw - 32px)',
          },
        } : {}),
      }
    })
  if (available.length) {
    const index = available.findIndex(step => step.title === currentTitle)
    current.value = index < 0 ? Math.min(current.value, available.length - 1) : index
    steps.value = available
  }
  else onboarding.stop()
}

watch(onboarding.active, () => {
  steps.value = []
  current.value = 0
  void refreshSteps()
}, { flush: 'sync' })
watch(router.currentRoute, () => onboarding.stop(), { flush: 'sync' })
watch(() => session.authentication.value?.user.id, () => onboarding.stop(), { flush: 'sync' })
onMounted(() => window.addEventListener('resize', refreshSteps))
onBeforeUnmount(() => {
  window.removeEventListener('resize', refreshSteps)
  onboarding.stop()
})
</script>

<template>
  <el-dialog
    :model-value="onboarding.welcomeOpen.value"
    class="onboarding-welcome"
    width="min(560px, calc(100vw - 32px))"
    align-center
    append-to-body
    :before-close="() => onboarding.complete('welcome')"
  >
    <template #header="{ titleId }">
      <h2
        :id="titleId"
        class="onboarding-welcome__title"
      >
        欢迎使用 Yumpoo，{{ session.authentication.value?.user.displayName }}
      </h2>
      <p class="onboarding-welcome__lead">
        这里是你的工作中台：组织项目协作、记录工时、跟进与你有关的通知。
      </p>
    </template>
    <ul class="onboarding-welcome__highlights">
      <li
        v-for="item in highlights"
        :key="item.label"
        :class="`is-${item.tone}`"
      >
        <span class="onboarding-welcome__icon"><el-icon aria-hidden="true"><component :is="item.icon" /></el-icon></span>
        <strong>{{ item.label }}</strong>
        <span class="onboarding-welcome__text">{{ item.text }}</span>
      </li>
    </ul>
    <template #footer>
      <el-button @click="onboarding.complete('welcome')">
        跳过
      </el-button>
      <el-button
        type="primary"
        @click="onboarding.start('welcome')"
      >
        开始导览（约 1 分钟）
      </el-button>
    </template>
  </el-dialog>
  <el-tour
    v-model="open"
    v-model:current="current"
    :target-area-clickable="false"
  >
    <el-tour-step
      v-for="step in steps"
      :key="step.title"
      v-bind="step"
    />
  </el-tour>
</template>

<style scoped>
.onboarding-welcome__title {
  margin: 0;
  color: var(--yp-text-primary);
  font: 600 20px / 28px var(--yp-font-heading);
}

.onboarding-welcome__lead {
  margin: var(--yp-space-1) 0 0;
  color: var(--yp-text-secondary);
}

.onboarding-welcome__highlights {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--yp-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.onboarding-welcome__highlights li {
  --tone: var(--yp-action-primary);

  display: grid;
  gap: var(--yp-space-1);
  padding: var(--yp-space-3);
  border: 1px solid var(--yp-border-subtle);
  border-radius: var(--yp-radius-md);
}

.onboarding-welcome__highlights li.is-green {
  --tone: var(--yp-status-green);
}

.onboarding-welcome__highlights li.is-purple {
  --tone: var(--yp-label-dark-purple);
}

.onboarding-welcome__highlights li.is-red {
  --tone: var(--yp-label-red);
}

.onboarding-welcome__icon {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 10px;
  background: color-mix(in srgb, var(--tone) 14%, transparent);
  color: var(--tone);
  font-size: 18px;
}

.onboarding-welcome__highlights strong {
  color: var(--yp-text-primary);
  font-size: 14px;
  font-weight: 600;
}

.onboarding-welcome__text {
  color: var(--yp-text-secondary);
  font-size: 12px;
  line-height: 18px;
}

@media (max-width: 640px) {
  .onboarding-welcome__highlights {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
