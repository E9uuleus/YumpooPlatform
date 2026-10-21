<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { TimeSessionFillMode, type TimerCandidate } from '@yumpoo/api-client'
import type { QuickPanelContext } from '@yumpoo/preload-contract'
import { ElInput } from 'element-plus'
import { ArrowRight, CircleCheck, Clock } from '@element-plus/icons-vue'
import { useSession } from '../../composables/useSession'
import { useTimeTracker } from '../../composables/useTimeTracker'
import { useTimerCandidates } from '../../composables/useTimerCandidates'
import { useHideCompleted } from '../../composables/useHideCompleted'
import { useDesktopSurfaceOpacity } from '../../composables/useDesktopSurfaceOpacity'
import { useDayTimeline } from '../../components/my-time/day/useDayTimeline'
import { HOUR, MIN_BLOCK_MS, parseDuration } from '../../components/my-time/day/dayModel'
import { addDays, todayIn } from '../../components/team/teamDashboardModel'
import HideCompletedSwitch from '../../components/timer/HideCompletedSwitch.vue'
import TimerIcon from '../../components/timer/TimerIcon.vue'
import QuickTimeRow from '../../components/desktop/QuickTimeRow.vue'
import { groupCandidates, placementHint, projectTones, quickPlan, shortDuration, spokenDuration, type DateChoice } from '../../components/desktop/quickTime'
import brandLogo from '../../assets/brand/logo.svg'

const bridge = window.yumpooDesktop?.quick
const pinnable = !!bridge?.setPinned
const pinned = ref(false)
const session = useSession()
const tracker = useTimeTracker()
useDesktopSurfaceOpacity()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const today = computed(() => todayIn(timezone.value, new Date(tracker.now.value)))
const candidates = useTimerCandidates(computed(() => session.phase.value === 'authenticated'))
const { hideCompleted, visibleCandidates } = useHideCompleted()

const context = ref<QuickPanelContext>({ projectId: null, projects: [], origin: 'bottom' })
const project = ref<string | null>(null)
const openId = ref<string | null>(null)
const dateChoice = ref<DateChoice>('today')
const pickedDate = ref(today.value)
const durationMs = ref(HOUR)
const custom = ref(false)
const customText = ref('')
const recording = ref<'idle' | 'saving' | 'done'>('idle')
const flashId = ref<string | null>(null)
const toast = ref('')
const toastVisible = ref(false)
const closing = ref(false)
const opening = ref(0)
const search = ref<InstanceType<typeof ElInput>>()
let toastTimer: ReturnType<typeof setTimeout> | undefined
let countFrame = 0
let countNext = false

const targetDate = computed(() => dateChoice.value === 'today' ? today.value : dateChoice.value === 'yesterday' ? addDays(today.value, -1) : pickedDate.value)
// Today feeds the header total and each row's reading; the target day, which may also be today, feeds the placement.
const todayDay = useDayTimeline(today, timezone)
const targetDay = useDayTimeline(targetDate, timezone)

const query = candidates.query
const inScope = computed(() => project.value ? candidates.items.value.filter(item => item.projectId === project.value) : candidates.items.value)
const shown = computed(() => visibleCandidates(inScope.value, query.value))
const hiddenCount = computed(() => inScope.value.length - shown.value.length)
const groups = computed(() => groupCandidates(shown.value, context.value.projects))
const chips = computed(() => {
  const list = groupCandidates(visibleCandidates(candidates.items.value, query.value), context.value.projects)
    .map(group => ({ id: group.projectId, name: group.projectName }))
  const locked = context.value.projects.find(item => item.id === project.value)
  return locked && !list.some(item => item.id === locked.id) ? [{ id: locked.id, name: locked.name }, ...list] : list
})
const tones = computed(() => projectTones(context.value.projects, chips.value.map(chip => chip.id)))
const todayMs = computed(() => new Map(todayDay.totals.value.map(total => [total.workItemId, total.ms])))
const total = ref(0)

const plan = computed(() => quickPlan({ blocks: targetDay.blocks.value, now: targetDay.now.value, workday: targetDay.workday.value,
  dayRange: targetDay.dayRange.value, limit: targetDay.limit.value }, durationMs.value))
const durationValid = computed(() => durationMs.value >= MIN_BLOCK_MS)
const hint = computed(() => !durationValid.value ? '至少记录 15 分钟'
  : !targetDay.page.value ? '正在读取当天记录…'
  : placementHint(plan.value, targetDate.value, today.value, timezone.value, targetDay.dayRange.value.end))
const canRecord = computed(() => durationValid.value && !!targetDay.page.value && plan.value.entries.length > 0 && !targetDay.busy.value && recording.value === 'idle')
const transformOrigin = computed(() => {
  const origin = context.value.origin
  if (context.value.projectId) return `${origin} center`
  return origin === 'top' || origin === 'bottom' ? `${origin} right` : `${origin} bottom`
})
const myDayPath = computed(() => `/workspace/${session.authentication.value?.user.workspaceSlug}/my-time/day/${today.value}`)

watch(customText, text => { if (custom.value) durationMs.value = parseDuration(text) ?? 0 })
watch(custom, value => { if (!value) customText.value = '' })
watch(() => todayDay.recordedMs.value, (to, from) => {
  cancelAnimationFrame(countFrame)
  if (!countNext) { total.value = to; return }
  // After our own save the total counts up to the new value with an ease-out over 520ms.
  countNext = false
  const started = performance.now()
  const step = (time: number) => {
    const progress = Math.min(1, (time - started) / 520)
    total.value = (from ?? to) + (to - (from ?? to)) * (1 - (1 - progress) ** 3)
    if (progress < 1) countFrame = requestAnimationFrame(step)
  }
  countFrame = requestAnimationFrame(step)
}, { immediate: true })

const offOpen = bridge?.onOpen(value => {
  pinned.value = value.pinned === true
  context.value = value
  project.value = value.projectId
  openId.value = null
  closing.value = false
  dateChoice.value = 'today'
  // The prewarmed panel can live for days; the date picker starts from the current day on every open.
  pickedDate.value = today.value
  custom.value = false
  durationMs.value = HOUR
  opening.value++
  if (query.value) query.value = ''
  else void candidates.load()
  void nextTick(() => search.value?.focus())
})
const offClosing = bridge?.onClosing(() => { closing.value = true })
const offPinned = bridge?.onPinned?.(value => { pinned.value = value })
onBeforeUnmount(() => { offOpen?.(); offClosing?.(); offPinned?.(); clearTimeout(toastTimer); cancelAnimationFrame(countFrame) })

function startResize(event: PointerEvent) {
  const grip = event.currentTarget as HTMLElement
  grip.setPointerCapture(event.pointerId)
  void bridge!.resize!(true)
  let ended = false
  const end = () => {
    if (ended) return
    ended = true
    void bridge!.resize!(false)
  }
  grip.addEventListener('pointerup', end, { once: true })
  grip.addEventListener('lostpointercapture', end, { once: true })
}

function toggle(item: TimerCandidate) {
  if (recording.value !== 'idle') return
  openId.value = openId.value === item.workItemId ? null : item.workItemId
}
function showToast(text: string) {
  toast.value = text
  toastVisible.value = true
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastVisible.value = false }, 1800)
}
async function record(item: TimerCandidate) {
  if (!canRecord.value) return
  const entries = plan.value.entries
  recording.value = 'saving'
  countNext = targetDate.value === today.value
  const result = await targetDay.create(TimeSessionFillMode.Gaps, [item.workItemId], entries)
  // Nothing new because the range was already recorded: create() has already said so, and there is nothing to celebrate.
  if (!result || !(result.createdCount || result.changedCount || result.deletedCount)) {
    countNext = false
    recording.value = 'idle'
    return
  }
  recording.value = 'done'
  const recorded = entries.reduce((sum, range) => sum + range.end - range.start, 0) - result.skippedDurationMs
  setTimeout(() => {
    openId.value = null
    recording.value = 'idle'
    flashId.value = item.workItemId
    showToast(`已记录 ${spokenDuration(recorded)} · ${item.title}`)
    setTimeout(() => { if (flashId.value === item.workItemId) flashId.value = null }, 900)
  }, 380)
}
async function startTimer(item: TimerCandidate) {
  if (!await tracker.start(item.workItemId)) return
  openId.value = null
  showToast(`已开始计时 · ${item.title}`)
}
function keydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  if (openId.value) openId.value = null
  else void bridge?.close()
}
</script>

<template>
  <div
    class="quick-time"
    @keydown="keydown"
  >
    <section
      :key="opening"
      class="quick-card"
      :class="{ 'is-closing': closing }"
      :style="{ transformOrigin }"
      aria-label="快捷工时"
    >
      <header
        class="quick-head"
        :class="{ 'is-draggable': pinnable }"
      >
        <img
          class="quick-logo"
          :src="brandLogo"
          alt=""
        >
        <strong>快捷工时</strong>
        <span class="quick-total"><Clock />今日 <b>{{ shortDuration(total) }}</b></span>
        <button
          v-if="pinnable"
          type="button"
          class="quick-icon"
          :class="{ 'is-pinned': pinned }"
          :aria-pressed="pinned"
          :aria-label="pinned ? '取消固定' : '固定小窗'"
          :title="pinned ? '取消固定' : '固定小窗'"
          @click="bridge!.setPinned!(!pinned)"
        >
          <TimerIcon name="pin" />
        </button>
        <button
          type="button"
          class="quick-icon"
          aria-label="偏好设置"
          title="偏好设置"
          @click="bridge?.openMain('/settings')"
        >
          <TimerIcon name="settings" />
        </button>
        <button
          v-if="pinned"
          type="button"
          class="quick-icon"
          aria-label="关闭"
          @click="bridge?.close()"
        >
          <TimerIcon name="close" />
        </button>
      </header>
      <label class="quick-search">
        <TimerIcon name="search" />
        <el-input
          ref="search"
          v-model="query"
          placeholder="搜索工作项（含已完成）"
          aria-label="搜索工作项"
        />
      </label>
      <div
        class="quick-projects"
        role="radiogroup"
        aria-label="项目"
      >
        <button
          type="button"
          class="quick-chip"
          role="radio"
          :aria-checked="!project"
          :class="{ 'is-active': !project }"
          @click="project = null"
        >
          全部
        </button>
        <button
          v-for="chip in chips"
          :key="chip.id"
          type="button"
          class="quick-chip"
          role="radio"
          :aria-checked="project === chip.id"
          :class="{ 'is-active': project === chip.id }"
          @click="project = chip.id"
        >
          <span
            class="quick-dot"
            :style="{ background: tones.get(chip.id) }"
          />{{ chip.name }}
        </button>
      </div>
      <div class="quick-list">
        <div
          v-for="group in groups"
          :key="group.projectId"
        >
          <div
            v-if="!project"
            class="quick-group"
          >
            {{ group.projectName }}
          </div>
          <TransitionGroup name="quick-row">
            <QuickTimeRow
              v-for="item in group.items"
              :key="item.workItemId"
              v-model:date-choice="dateChoice"
              v-model:picked-date="pickedDate"
              v-model:duration-ms="durationMs"
              v-model:custom="custom"
              v-model:custom-text="customText"
              :item="item"
              :today-ms="todayMs.get(item.workItemId) ?? 0"
              :expanded="openId === item.workItemId"
              :faded="hideCompleted && item.completed"
              :flashing="flashId === item.workItemId"
              :today="today"
              :hint="hint"
              :can-record="canRecord"
              :recording="openId === item.workItemId ? recording : 'idle'"
              :timer-enabled="tracker.timerEnabled.value"
              @toggle="toggle(item)"
              @record="record(item)"
              @start="startTimer(item)"
            />
          </TransitionGroup>
        </div>
        <p
          v-if="!groups.length"
          class="quick-empty"
        >
          {{ candidates.loading.value ? '正在加载工作项…' : candidates.problem.value
            || (project ? '这个项目里暂无分配给你或最近记录的工作项，可以在上方搜索。' : '暂无工作项，可以在上方搜索。') }}
        </p>
        <button
          v-else-if="candidates.nextOffset.value !== null"
          type="button"
          class="quick-more"
          :disabled="candidates.loading.value"
          @click="candidates.load(true)"
        >
          加载更多
        </button>
      </div>
      <footer class="quick-foot">
        <HideCompletedSwitch :hidden-count="hiddenCount" />
        <button
          type="button"
          class="quick-link"
          @click="bridge?.openMain(myDayPath)"
        >
          我的一天<ArrowRight />
        </button>
      </footer>
      <span
        v-if="bridge?.resize"
        class="quick-grip"
        aria-hidden="true"
        @pointerdown.prevent="startResize"
      ><TimerIcon name="resize" /></span>
      <div
        class="quick-toast"
        :class="{ 'is-visible': toastVisible }"
        role="status"
      >
        <CircleCheck />{{ toast }}
      </div>
    </section>
  </div>
</template>

<style scoped>
.quick-time{box-sizing:border-box;height:100vh;padding:12px;font-family:var(--yp-font-family);font-size:13px;color:var(--yp-text-primary)}
.quick-card{position:relative;box-sizing:border-box;display:flex;flex-direction:column;height:100%;overflow:hidden;border-radius:var(--yp-timer-radius);
  border:1px solid var(--yp-timer-hairline);background:color-mix(in srgb,var(--yp-bg-raised) var(--yp-desktop-surface-alpha,96%),transparent);
  box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight);user-select:none;animation:quick-in .22s var(--yp-timer-ease) both}
.quick-card.is-closing{opacity:0;transform:translateY(10px) scale(.96);transition:opacity .18s var(--yp-timer-ease),transform .18s var(--yp-timer-ease)}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0}
button:disabled{cursor:default;opacity:.45}
button:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:-2px}
.quick-head{display:flex;align-items:center;gap:8px;height:44px;padding:0 8px 0 12px;border-bottom:1px solid var(--yp-timer-hairline);flex-shrink:0}
.quick-logo{width:22px;height:22px}
.quick-head strong{font-weight:600}
.quick-total{display:inline-flex;align-items:center;gap:4px;height:24px;margin-left:auto;padding:0 9px;border-radius:999px;background:var(--yp-timer-sunken);color:var(--yp-text-secondary);font-size:12px}
.quick-total svg{width:13px;height:13px}
.quick-total b{color:var(--yp-text-primary);font-weight:500;font-variant-numeric:tabular-nums}
.quick-icon{display:grid;place-items:center;width:28px;height:28px;border-radius:8px;color:var(--yp-text-secondary)}
.quick-icon:hover{background:var(--yp-timer-hover);color:var(--yp-text-primary)}
.quick-icon svg{width:16px;height:16px}
.quick-icon.is-pinned{color:var(--yp-timer-accent);background:color-mix(in srgb,var(--yp-timer-accent) 12%,transparent)}
.quick-head.is-draggable{app-region:drag}
.quick-head.is-draggable button{app-region:no-drag}
.quick-grip{position:absolute;right:4px;bottom:4px;width:16px;height:16px;cursor:nwse-resize;opacity:.35;color:var(--yp-text-secondary)}
.quick-grip svg{transform:rotate(90deg)}
.quick-card:hover .quick-grip{opacity:.6}
.quick-search{display:flex;align-items:center;gap:6px;height:32px;margin:8px 10px 6px;padding:0 10px;border-radius:8px;background:var(--yp-timer-sunken);color:var(--yp-text-muted);flex-shrink:0}
.quick-search svg{width:15px;height:15px}
.quick-search :deep(.el-input){flex:1;min-width:0;--el-input-height:30px}
.quick-search :deep(.el-input__wrapper){padding:0;background:none;box-shadow:none}
.quick-search :deep(.el-input__inner){height:30px;color:var(--yp-text-primary);font:13px var(--yp-font-family)}
.quick-search :deep(.el-input__inner::placeholder){color:var(--yp-text-placeholder)}
.quick-projects{display:flex;gap:6px;padding:0 10px 6px;overflow-x:auto;scrollbar-width:none;flex-shrink:0}
.quick-chip{display:inline-flex;align-items:center;gap:5px;height:24px;padding:0 9px;border:1px solid var(--yp-timer-hairline);border-radius:999px;color:var(--yp-text-secondary);font-size:12px;white-space:nowrap;flex-shrink:0}
.quick-chip.is-active{border-color:var(--yp-timer-accent);background:color-mix(in srgb,var(--yp-timer-accent) 12%,transparent);color:var(--yp-timer-accent)}
.quick-dot{width:7px;height:7px;border-radius:50%}
.quick-list{flex:1;min-height:0;overflow:auto;padding:2px 8px 8px}
.quick-group{padding:8px 6px 3px;color:var(--yp-text-muted);font-size:11px}
.quick-empty{margin:24px 12px;color:var(--yp-text-muted);font-size:12.5px;line-height:1.6;text-align:center}
.quick-more{display:block;margin:6px auto 0;padding:4px 10px;border-radius:8px;color:var(--yp-timer-accent);font-size:12.5px}
.quick-foot{display:flex;align-items:center;gap:8px;height:44px;padding:0 12px;border-top:1px solid var(--yp-timer-hairline);flex-shrink:0}
.quick-link{display:inline-flex;align-items:center;gap:2px;margin-left:auto;color:var(--yp-timer-accent);font-size:12.5px}
.quick-link svg{width:13px;height:13px}
.quick-toast{position:absolute;left:12px;right:12px;bottom:52px;display:flex;align-items:center;gap:8px;height:34px;padding:0 12px;border-radius:9px;
  background:var(--yp-text-primary);color:var(--yp-bg-raised);font-size:12.5px;opacity:0;transform:translateY(6px);pointer-events:none;
  transition:opacity .18s var(--yp-timer-ease),transform .22s var(--yp-timer-ease)}
.quick-toast.is-visible{opacity:1;transform:none}
.quick-toast svg{width:15px;height:15px;flex-shrink:0}
.quick-row-enter-active,.quick-row-leave-active{transition:grid-template-rows .26s var(--yp-timer-ease),opacity .2s}
.quick-row-enter-from,.quick-row-leave-to{grid-template-rows:0fr;opacity:0}
@keyframes quick-in{from{opacity:0;transform:translateY(10px) scale(.96)}to{opacity:1;transform:none}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
</style>
