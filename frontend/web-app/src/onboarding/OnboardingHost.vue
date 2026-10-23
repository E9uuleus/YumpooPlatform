<script setup lang="ts">
import { DataAnalysis, FolderOpened, Message, Timer } from '@element-plus/icons-vue'
import { ElButton, ElDialog, ElIcon, ElTour, ElTourStep } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue'
import { useRouter } from 'vue-router'
import { useSession } from '../composables/useSession'
import { openDocs } from '../docs-site/openDocs'
import { TOURS, type TourStep } from './tours'
import { useOnboarding } from './useOnboarding'

const session = useSession()
const onboarding = useOnboarding()
const router = useRouter()
const steps = ref<TourStep[]>([])
const current = ref(0)
const updating = computed(() => onboarding.active.value?.mode === 'update')
let resizeFrame: number | undefined
const open = computed({
  get: () => steps.value.length > 0,
  set: value => {
    const id = onboarding.active.value?.id
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

// 主目标不可见时改用备用目标（如二级导航收起时指向展开按钮），都不可见的步骤跳过。
function locate({ fallback, ...step }: TourStep): TourStep | undefined {
  delete step.since
  if (!step.target || visible(step.target)) return step
  return fallback && visible(fallback.target) ? { ...step, ...fallback } : undefined
}

async function refreshSteps(): Promise<void> {
  const tour = onboarding.active.value
  if (!tour) return
  await nextTick()
  if (onboarding.active.value !== tour) return
  const currentTitle = steps.value[current.value]?.title
  const available = TOURS[tour.id].steps
    .filter(step => tour.mode !== 'update' || (step.since ?? 1) > tour.from)
    .map(locate)
    .filter(step => step !== undefined)
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

function browseDocs(): void {
  onboarding.complete('welcome')
  openDocs(router, '/docs')
}

function cancelResize(): void {
  if (resizeFrame !== undefined) cancelAnimationFrame(resizeFrame)
  resizeFrame = undefined
}

function scheduleResize(): void {
  if (resizeFrame !== undefined || !onboarding.active.value) return
  resizeFrame = requestAnimationFrame(() => {
    resizeFrame = undefined
    void refreshSteps()
  })
}

watch(onboarding.active, () => {
  cancelResize()
  steps.value = []
  current.value = 0
  void refreshSteps()
}, { flush: 'sync' })
watch(router.currentRoute, () => onboarding.stop(), { flush: 'sync' })
watch(() => session.authentication.value?.user.id, () => onboarding.stop(), { flush: 'sync' })
onMounted(() => window.addEventListener('resize', scheduleResize))
onBeforeUnmount(() => {
  window.removeEventListener('resize', scheduleResize)
  cancelResize()
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
      <div class="onboarding-welcome__footer">
        <el-button
          class="onboarding-welcome__docs"
          text
          @click="browseDocs"
        >
          浏览帮助中心
        </el-button>
        <el-button @click="onboarding.complete('welcome')">
          跳过
        </el-button>
        <el-button
          type="primary"
          @click="onboarding.start('welcome')"
        >
          开始导览（约 1 分钟）
        </el-button>
      </div>
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
    >
      <template
        v-if="updating"
        #header
      >
        <span
          role="heading"
          class="el-tour__title"
        ><span class="onboarding-new-tag">新功能</span>{{ step.title }}</span>
      </template>
    </el-tour-step>
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

.onboarding-welcome__footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.onboarding-welcome__docs {
  margin-right: auto;
}

.onboarding-new-tag {
  margin-right: var(--yp-space-2);
  padding: 0 6px;
  border-radius: var(--yp-radius-sm);
  background: color-mix(in srgb, var(--yp-action-primary) 14%, transparent);
  color: var(--yp-action-primary);
  font-size: 12px;
  font-weight: 500;
  vertical-align: 1px;
}

@media (max-width: 640px) {
  .onboarding-welcome__highlights {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>

