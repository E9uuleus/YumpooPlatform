<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { TimeSessionFillMode, WorkItemStatusCategory, type TimerCandidate, type WorkItemDetail } from '@yumpoo/api-client'
import type { QuickPanelContext } from '@yumpoo/preload-contract'
import { ElInput } from 'element-plus'
import { ArrowRight, CircleCheck, Clock, DocumentAdd, WarningFilled } from '@element-plus/icons-vue'
import { useSession } from '../../composables/useSession'
import { useTimeTracker } from '../../composables/useTimeTracker'
import { useTimerCandidates } from '../../composables/useTimerCandidates'
import { useInfiniteSentinel } from '../../composables/useInfiniteSentinel'
import { useHideCompleted } from '../../composables/useHideCompleted'
import { useDesktopSurfaceOpacity } from '../../composables/useDesktopSurfaceOpacity'
import { announceWorkItemChanged } from '../../composables/workItemChanges'
import { useDayTimeline } from '../../components/my-time/day/useDayTimeline'
import { atMinute, formatClock, HOUR, MIN_BLOCK_MS, parseClock, parseDuration } from '../../components/my-time/day/dayModel'
import { addDays, todayIn } from '../../components/team/teamDashboardModel'
import HideCompletedSwitch from '../../components/timer/HideCompletedSwitch.vue'
import TimerIcon from '../../components/timer/TimerIcon.vue'
import QuickTimeRow from '../../components/desktop/QuickTimeRow.vue'
import QuickCreateRow from '../../components/desktop/QuickCreateRow.vue'
import QuickFixedRow from '../../components/desktop/QuickFixedRow.vue'
import { matchCategories, type TimeCategoryOption } from '../../components/timer/timeCategories'
import { groupCandidates, placementHint, projectTones, quickPlan, rangeFromPlan, rangeProblem, rangeRecordable, shortDuration, spokenDuration,
  type DateChoice, type QuickItemStatus } from '../../components/desktop/quickTime'
import brandLogo from '../../assets/brand/logo.svg'

const bridge = window.yumpooDesktop?.quick
const pinnable = !!bridge?.setPinned
const pinned = ref(false)
const session = useSession()
const tracker = useTimeTracker()
useDesktopSurfaceOpacity()
const timezone = computed(() => session.authentication.value?.company.timezone ?? 'Asia/Shanghai')
const today = computed(() => todayIn(timezone.value, new Date(tracker.now.value)))
const project = ref<string | null>(null)
const candidates = useTimerCandidates(computed(() => session.phase.value === 'authenticated'), {
  projects: computed(() => project.value ? [project.value] : []),
})
const { hideCompleted } = useHideCompleted()
const list = ref<HTMLElement>()
const sentinel = ref<HTMLElement>()
useInfiniteSentinel(list, sentinel, () => candidates.items.value.length,
  () => candidates.nextOffset.value !== null && !candidates.loading.value && !candidates.problem.value, () => candidates.load(true))

const context = ref<QuickPanelContext>({ projectId: null, projects: [], origin: 'bottom' })
const openId = ref<string | null>(null)
const dateChoice = ref<DateChoice>('today')
const pickedDate = ref(today.value)
const durationMs = ref(HOUR)
const custom = ref(false)
const customText = ref('')
const rangeMode = ref(false)
const rangeStart = ref(0)
const rangeEnd = ref(0)
const recording = ref<'idle' | 'saving' | 'done'>('idle')
const flashId = ref<string | null>(null)
const creating = ref(false)
const createTitle = ref('')
const createKey = ref(0)
/** A created item to open once the list has reloaded; it is added locally when the first page does not reach it. */
let reveal: TimerCandidate | undefined
const toast = ref('')
const toastVisible = ref(false)
const toastFailed = ref(false)
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
const shown = candidates.items
const hiddenCount = candidates.hiddenCount
const groups = computed(() => groupCandidates(shown.value, context.value.projects))
const chips = computed(() => {
  const counts = candidates.projectCounts.value
  const list = context.value.projects.filter(item => counts.some(count => count.projectId === item.id))
    .map(item => ({ id: item.id, name: item.name }))
  for (const item of counts) if (!list.some(project => project.id === item.projectId)) list.push({ id: item.projectId, name: item.projectName })
  const locked = context.value.projects.find(item => item.id === project.value)
  return locked && !list.some(item => item.id === locked.id) ? [{ id: locked.id, name: locked.name }, ...list] : list
})
const tones = computed(() => projectTones(context.value.projects, chips.value.map(chip => chip.id)))
const strip = ref<HTMLElement>()
/** Which ends of the project strip hide more chips; each such end fades out. */
const edges = ref({ start: false, end: false })
const STRIP_FADE = 24
const todayMs = computed(() => new Map(todayDay.totals.value.map(total => [total.key, total.ms])))
/** Categories sit above the work items; with a project chip they are recorded against that project. */
const categories = computed(() => matchCategories(query.value, project.value))
const total = ref(0)

const plan = computed(() => quickPlan({ blocks: targetDay.blocks.value, now: targetDay.now.value, workday: targetDay.workday.value,
  dayRange: targetDay.dayRange.value, limit: targetDay.limit.value }, durationMs.value))
const durationValid = computed(() => durationMs.value >= MIN_BLOCK_MS)
const hint = computed(() => !durationValid.value ? '至少记录 15 分钟'
  : !targetDay.page.value ? '正在读取当天记录…'
  : placementHint(plan.value, targetDate.value, today.value, timezone.value, targetDay.dayRange.value.end))
const range = computed(() => ({ start: rangeStart.value, end: rangeEnd.value }))
const rangeIssue = computed(() => rangeProblem(range.value, targetDay.dayRange.value, targetDay.limit.value))
const recordable = computed(() => rangeIssue.value ? 0 : rangeRecordable(range.value, targetDay.blocks.value, targetDay.now.value))
const rangeNote = computed(() => rangeIssue.value || !targetDay.page.value ? rangeIssue.value
  : !recordable.value ? '这段时间已全部记录'
  : recordable.value < range.value.end - range.value.start ? '与已有记录重叠的部分会跳过' : '')
const entries = computed(() => rangeMode.value ? [range.value] : plan.value.entries)
const canRecord = computed(() => (rangeMode.value ? recordable.value > 0 : durationValid.value && plan.value.entries.length > 0)
  && !!targetDay.page.value && !targetDay.busy.value && recording.value === 'idle')
const transformOrigin = computed(() => {
  const origin = context.value.origin
  if (context.value.projectId) return `${origin} center`
  return origin === 'top' || origin === 'bottom' ? `${origin} right` : `${origin} bottom`
})
const myDayPath = computed(() => `/workspace/${session.authentication.value?.user.workspaceSlug}/my-time/day/${today.value}`)

watch(customText, text => { if (custom.value) durationMs.value = parseDuration(text) ?? 0 })
watch(custom, value => { if (!value) customText.value = '' })
// Range mode keeps the duration and the range in step: a chip or custom duration moves the end, edited ends recompute the duration.
let autoDurationMs = HOUR
watch(rangeMode, value => {
  // Leaving an unfinished range (end before start) gives the automatic plan back the length it had.
  if (!value) { if (!durationValid.value) durationMs.value = autoDurationMs; return }
  custom.value = false
  autoDurationMs = durationValid.value ? durationMs.value : HOUR
  const next = rangeFromPlan(plan.value, autoDurationMs, targetDay.limit.value)
  rangeStart.value = next.start
  rangeEnd.value = next.end
})
watch(durationMs, ms => { if (rangeMode.value && ms > 0 && rangeEnd.value - rangeStart.value !== ms) rangeEnd.value = rangeStart.value + ms })
watch(range, value => { if (rangeMode.value) durationMs.value = Math.max(0, value.end - value.start) })
watch(targetDate, (to, from) => {
  if (!rangeMode.value) return
  // Another day keeps the same wall-clock times; the day's own end (24:00) stays the day's end.
  const end = atMinute(from, 1440, timezone.value)
  const move = (ms: number) => atMinute(to, parseClock(formatClock(ms, timezone.value, end)) ?? 0, timezone.value)
  rangeStart.value = move(rangeStart.value)
  rangeEnd.value = move(rangeEnd.value)
})
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

function revealCreated() {
  const item = reveal
  if (candidates.loading.value || !item) return
  reveal = undefined
  if (!candidates.items.value.some(candidate => candidate.workItemId === item.workItemId)) candidates.items.value = [item, ...candidates.items.value]
  rangeMode.value = false
  openId.value = item.workItemId
  void nextTick(() => list.value?.querySelector(`[data-work-item-id="${item.workItemId}"]`)?.scrollIntoView({ block: 'nearest' }))
}
watch(() => candidates.loading.value, revealCreated)

function measureEdges() {
  const el = strip.value
  if (!el) return
  edges.value = { start: el.scrollLeft > 1, end: el.scrollLeft < el.scrollWidth - el.clientWidth - 1 }
}
/** A mouse wheel only scrolls vertically; over the project strip it moves the chips sideways. */
function scrollStrip(event: WheelEvent) {
  const el = strip.value
  if (!el || el.scrollWidth <= el.clientWidth || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return
  event.preventDefault()
  el.scrollLeft += event.deltaY
  measureEdges()
}
// The card, and with it the strip, is rebuilt on every open; resizing the panel changes how many chips fit.
watch(strip, (el, _, onCleanup) => {
  if (!el || typeof ResizeObserver === 'undefined') return
  const observer = new ResizeObserver(measureEdges)
  observer.observe(el)
  onCleanup(() => observer.disconnect())
})
// The chosen project stays clear of the fades, e.g. when the panel opens from a project far along the strip.
watch([project, chips, strip], () => {
  const el = strip.value
  const chip = el?.querySelector<HTMLElement>('.quick-chip.is-active')
  if (el && chip) {
    const left = chip.offsetLeft, right = left + chip.offsetWidth
    if (left < el.scrollLeft + STRIP_FADE) el.scrollLeft = Math.max(0, left - STRIP_FADE)
    else if (right > el.scrollLeft + el.clientWidth - STRIP_FADE) el.scrollLeft = right - el.clientWidth + STRIP_FADE
  }
  measureEdges()
}, { flush: 'post' })

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
  rangeMode.value = false
  creating.value = false
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

/** `key` is the work item id or the category key of the row to open. */
function toggle(key: string) {
  if (recording.value !== 'idle') return
  openId.value = openId.value === key ? null : key
  rangeMode.value = false
}
function showToast(text: string, failed = false) {
  toast.value = text
  toastFailed.value = failed
  toastVisible.value = true
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toastVisible.value = false }, failed ? 3200 : 1800)
}
async function record(key: string, title: string) {
  if (!canRecord.value) return
  const ranges = entries.value
  recording.value = 'saving'
  countNext = targetDate.value === today.value
  const result = await targetDay.create(TimeSessionFillMode.Gaps, [key], ranges)
  // Nothing new because the range was already recorded: create() has already said so, and there is nothing to celebrate.
  if (!result || !(result.createdCount || result.changedCount || result.deletedCount)) {
    countNext = false
    recording.value = 'idle'
    return
  }
  recording.value = 'done'
  const recorded = ranges.reduce((sum, range) => sum + range.end - range.start, 0) - result.skippedDurationMs
  setTimeout(() => {
    openId.value = null
    recording.value = 'idle'
    flashId.value = key
    showToast(`已记录 ${spokenDuration(recorded)} · ${title}`)
    setTimeout(() => { if (flashId.value === key) flashId.value = null }, 900)
  }, 380)
}
function statusResolved(item: TimerCandidate, detail: WorkItemDetail, status: QuickItemStatus) {
  Object.assign(item, status, { statusCategory: detail.statusCategory })
}
function statusChanged(item: TimerCandidate, detail: WorkItemDetail, status: QuickItemStatus) {
  statusResolved(item, detail, status)
  // The personal completion definition lives on the server; the reload below corrects this guess.
  item.completed = detail.statusCategory === WorkItemStatusCategory.Done || detail.statusCategory === WorkItemStatusCategory.Canceled
  void candidates.load()
  showToast(`已改为「${status.statusName}」· ${item.title}`)
  announceWorkItemChanged({ projectId: item.projectId, workItemId: item.workItemId })
}
function startCreate(title = '') {
  createTitle.value = title
  createKey.value++
  creating.value = true
}
function created(detail: WorkItemDetail, projectName: string, status: QuickItemStatus) {
  creating.value = false
  reveal = { workItemId: detail.id, projectId: detail.projectId, projectName, itemNo: detail.itemNo, title: detail.title,
    contentName: detail.contentName, contentColorToken: detail.contentColorToken, statusCategory: detail.statusCategory, ...status, completed: false,
    assignedToMe: true, lastTrackedAt: null, ownDurationMs: 0 }
  showToast(`已创建 #${detail.itemNo} · ${detail.title}`)
  announceWorkItemChanged({ projectId: detail.projectId, workItemId: detail.id })
  // Clearing the search or switching the project chip reloads the list by itself.
  const reloads = !!query.value || (!!project.value && project.value !== detail.projectId)
  if (project.value) project.value = detail.projectId
  query.value = ''
  if (!reloads) void candidates.load().then(revealCreated)
}
async function startTimer(key: string, title: string) {
  if (!await tracker.start(key)) return
  openId.value = null
  showToast(`已开始计时 · ${title}`)
}
function keydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  event.preventDefault()
  if (creating.value) creating.value = false
  else if (openId.value) openId.value = null
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
          placeholder="搜索工作项或分类（含已完成）"
          aria-label="搜索工作项或分类"
        />
        <button
          type="button"
          class="quick-icon quick-search__add"
          aria-label="新建工作项"
          title="新建工作项"
          @click.prevent="startCreate()"
        >
          <DocumentAdd />
        </button>
      </label>
      <div
        ref="strip"
        class="quick-projects"
        :class="{ 'has-start': edges.start, 'has-end': edges.end }"
        role="radiogroup"
        aria-label="项目"
        @wheel="scrollStrip"
        @scroll.passive="measureEdges"
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
          :title="chip.name"
          @click="project = chip.id"
        >
          <span
            class="quick-dot"
            :style="{ background: tones.get(chip.id) }"
          /><span class="quick-chip__name">{{ chip.name }}</span>
        </button>
      </div>
      <div
        ref="list"
        class="quick-list"
      >
        <QuickCreateRow
          v-if="creating"
          :key="createKey"
          :preferred="[project, context.projectId]"
          :initial-title="createTitle"
          :user-id="session.authentication.value?.user.id ?? ''"
          :tones="tones"
          @close="creating = false"
          @created="created"
          @failed="showToast($event, true)"
        />
        <QuickFixedRow
          v-if="categories.length"
          v-model:date-choice="dateChoice"
          v-model:picked-date="pickedDate"
          v-model:duration-ms="durationMs"
          v-model:custom="custom"
          v-model:custom-text="customText"
          v-model:range-mode="rangeMode"
          v-model:range-start="rangeStart"
          v-model:range-end="rangeEnd"
          :categories="categories"
          :open-key="openId"
          :flash-key="flashId"
          :today-ms="todayMs"
          :today="today"
          :hint="hint"
          :target-date="targetDate"
          :timezone="timezone"
          :day-range="targetDay.dayRange.value"
          :range-note="rangeNote"
          :range-invalid="!!rangeIssue"
          :can-record="canRecord"
          :recording="recording"
          :timer-enabled="tracker.timerEnabled.value"
          @toggle="(category: TimeCategoryOption) => toggle(category.key)"
          @record="(category: TimeCategoryOption) => record(category.key, category.label)"
          @start="(category: TimeCategoryOption) => startTimer(category.key, category.label)"
        />
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
            <template
              v-for="{ key, item, parent, child, last } in group.rows"
              :key="key"
            >
              <div
                v-if="!item"
                class="quick-parent"
                :class="{ 'is-completed': hideCompleted && parent!.completed }"
                :title="`父工作项：${parent!.title}`"
              >
                <span class="quick-parent__bar" /><span class="quick-parent__title">{{ parent!.title }}</span>
              </div>
              <QuickTimeRow
                v-else
                v-model:date-choice="dateChoice"
                v-model:picked-date="pickedDate"
                v-model:duration-ms="durationMs"
                v-model:custom="custom"
                v-model:custom-text="customText"
                v-model:range-mode="rangeMode"
                v-model:range-start="rangeStart"
                v-model:range-end="rangeEnd"
                :data-work-item-id="item.workItemId"
                :item="item"
                :child="child"
                :continued="!last"
                :today-ms="todayMs.get(item.workItemId) ?? 0"
                :expanded="openId === item.workItemId"
                :faded="hideCompleted && item.completed"
                :flashing="flashId === item.workItemId"
                :today="today"
                :hint="hint"
                :target-date="targetDate"
                :timezone="timezone"
                :day-range="targetDay.dayRange.value"
                :range-note="rangeNote"
                :range-invalid="!!rangeIssue"
                :can-record="canRecord"
                :recording="openId === item.workItemId ? recording : 'idle'"
                :timer-enabled="tracker.timerEnabled.value"
                @toggle="toggle(item.workItemId)"
                @record="record(item.workItemId, item.title)"
                @start="startTimer(item.workItemId, item.title)"
                @status-changed="(detail, status) => statusChanged(item, detail, status)"
                @status-resolved="(detail, status) => statusResolved(item, detail, status)"
                @status-failed="showToast($event, true)"
              />
            </template>
          </TransitionGroup>
        </div>
        <p
          v-if="!groups.length"
          class="quick-empty"
        >
          {{ candidates.loading.value ? '正在加载工作项…' : candidates.problem.value
            || (query.trim() ? '没有找到匹配的工作项。' : project ? '这个项目里暂无分配给你或最近记录的工作项，可以在上方搜索。' : '暂无工作项，可以在上方搜索。') }}
          <button
            v-if="query.trim() && !candidates.loading.value && !candidates.problem.value && !creating"
            type="button"
            class="quick-empty__create"
            @click="startCreate(query.trim())"
          >
            <DocumentAdd />新建「{{ query.trim() }}」
          </button>
        </p>
        <p
          v-if="candidates.loading.value && shown.length"
          class="quick-status"
        >
          正在加载更多工作项…
        </p>
        <p
          v-else-if="candidates.problem.value && shown.length"
          class="quick-status"
          role="alert"
        >
          加载更多失败 <button
            type="button"
            @click="candidates.load(true)"
          >
            重试
          </button>
        </p>
        <div
          v-if="candidates.nextOffset.value !== null"
          ref="sentinel"
          class="quick-sentinel"
          aria-hidden="true"
        />
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
        :role="toastFailed ? 'alert' : 'status'"
      >
        <WarningFilled v-if="toastFailed" /><CircleCheck v-else /><span>{{ toast }}</span>
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
.quick-head{display:flex;align-items:center;gap:8px;height:44px;padding:0 8px 0 12px;flex-shrink:0}
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
.quick-projects{position:relative;display:flex;gap:6px;padding:0 10px 6px;overflow-x:auto;scrollbar-width:none;flex-shrink:0}
.quick-projects::-webkit-scrollbar{display:none}
.quick-projects.has-start{mask-image:linear-gradient(to right,transparent,black 24px)}
.quick-projects.has-end{mask-image:linear-gradient(to left,transparent,black 24px)}
.quick-projects.has-start.has-end{mask-image:linear-gradient(to right,transparent,black 24px,black calc(100% - 24px),transparent)}
.quick-projects .quick-chip{max-width:160px}
.quick-projects .quick-dot{flex-shrink:0}
.quick-chip__name{min-width:0;overflow:hidden;text-overflow:ellipsis}
.quick-chip{display:inline-flex;align-items:center;gap:5px;height:24px;padding:0 9px;border-radius:999px;color:var(--yp-text-secondary);font-size:12px;white-space:nowrap;flex-shrink:0;background:transparent;}
.quick-chip:hover:not(.is-active){background:var(--yp-bg-hover)}
.quick-chip.is-active{background:var(--yp-action-primary);color:var(--yp-on-solid);}
.quick-dot{width:7px;height:7px;border-radius:50%}
.quick-list{flex:1;min-height:0;overflow:auto;padding:2px 8px 8px}
.quick-group{padding:8px 6px 3px;color:var(--yp-text-muted);font-size:11px}
.quick-empty{margin:24px 12px;color:var(--yp-text-muted);font-size:12.5px;line-height:1.6;text-align:center}
.quick-empty__create{display:flex;align-items:center;justify-content:center;gap:4px;max-width:100%;margin:8px auto 0;color:var(--yp-timer-accent);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.quick-empty__create svg{width:14px;height:14px;flex-shrink:0}
.quick-search__add{width:24px;height:24px;margin-right:-6px;flex-shrink:0}
.quick-search__add svg{width:15px;height:15px}
.quick-status{margin:6px 0;color:var(--yp-text-muted);font-size:12px;text-align:center}
.quick-status button{color:var(--yp-timer-accent)}
.quick-sentinel{height:1px}
.quick-foot{display:flex;align-items:center;gap:8px;height:44px;padding:0 12px;flex-shrink:0}
.quick-link{display:inline-flex;align-items:center;gap:2px;margin-left:auto;color:var(--yp-timer-accent);font-size:12.5px}
.quick-link svg{width:13px;height:13px}
.quick-toast{position:absolute;left:12px;right:12px;bottom:52px;display:flex;align-items:center;gap:8px;height:34px;padding:0 12px;border-radius:9px;
  background:var(--yp-text-primary);color:var(--yp-bg-raised);font-size:12.5px;opacity:0;transform:translateY(6px);pointer-events:none;
  transition:opacity .18s var(--yp-timer-ease),transform .22s var(--yp-timer-ease)}
.quick-toast.is-visible{opacity:1;transform:none}
.quick-toast svg{width:15px;height:15px;flex-shrink:0}
.quick-toast span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.quick-row-enter-active,.quick-row-leave-active{transition:grid-template-rows .26s var(--yp-timer-ease),opacity .2s}
.quick-row-enter-from,.quick-row-leave-to{grid-template-rows:0fr;opacity:0}
@keyframes quick-in{from{opacity:0;transform:translateY(10px) scale(.96)}to{opacity:1;transform:none}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
.quick-parent{display:flex;align-items:center;gap:10px;min-width:0;padding:7px 6px;color:var(--yp-text-secondary);font-size:12.5px}
.quick-parent__bar{width:4px;align-self:stretch;flex-shrink:0;border-radius:2px;background:var(--yp-border-strong)}
.quick-parent__title{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.quick-parent.is-completed .quick-parent__title{color:var(--yp-text-muted);text-decoration:line-through}
</style>
