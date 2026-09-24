<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { TimerOrbChange, TimerOrbLayout, TimerPreferences, TimerPreferencesChange, TimerWindowMode } from '@yumpoo/preload-contract'
import { ListTimerCandidatesScopeEnum, type TimerCandidate } from '@yumpoo/api-client'
import { useTimeTracker } from '../../composables/useTimeTracker'
import { useTimerCandidates } from '../../composables/useTimerCandidates'
import TimerIcon from './TimerIcon.vue'
import TimerDigits from './TimerDigits.vue'
import TimerDock from './TimerDock.vue'
import TimerOrb from './TimerOrb.vue'
import TimerSettings from './TimerSettings.vue'
import WorkItemGlyph from './WorkItemGlyph.vue'

const CAPSULE_WIDTH = 216
const props = withDefaults(defineProps<{ initialExpanded?: boolean; floating?: boolean; orbLayout?: TimerOrbLayout }>(), { initialExpanded: true, floating: false, orbLayout: () => ({ size: 64, side: null, detailWidth: 0 }) })
const emit = defineEmits<{ hide: []; mode: [expanded: boolean]; drag: [event: PointerEvent]; orb: [layout: TimerOrbLayout] }>()
const desktop = window.yumpooDesktop?.timer
const router = useRouter()
const tracker = useTimeTracker()
const expanded = ref(props.initialExpanded)
const view = ref<'picker' | 'settings'>('picker')
const orb = ref<TimerOrbLayout>(props.orbLayout ?? { size: 64, side: null, detailWidth: 0 })
const preferences = ref<TimerPreferences>()
const layoutElement = ref<HTMLElement>()
const detailsOpen = ref(false)
const pointerInside = ref(false)
const dragging = ref(false)
let opening = false
let suppressToggle = false
let detailRevision = 0
let layoutRevision = 0
let hoverTimer: ReturnType<typeof setTimeout> | undefined
let leaveTimer: ReturnType<typeof setTimeout> | undefined
let mounted = false
const pinned = ref(true)
const notice = ref('')
const search = ref<HTMLInputElement>()
const results = ref<HTMLElement>()
const selected = ref(0)
const running = computed(() => tracker.current.value?.session)
const resume = computed(() => tracker.current.value?.recentItems[0])
const needsCandidates = computed(() => expanded.value || !!running.value || !!resume.value)
const { query, scope, items, loading, problem, nextOffset, searching, load } = useTimerCandidates(needsCandidates)
const title = computed(() => running.value ? tracker.current.value?.workItemTitle ?? '不可见工作项' : resume.value?.title)
const candidates = computed(() => items.value.filter(item => item.workItemId !== (running.value?.workItemId ?? (saved.value ? resume.value?.workItemId : undefined))))
const focusedItem = ref<TimerCandidate>()
const focusedId = computed(() => running.value ? running.value.workItemId : resume.value?.workItemId)
const currentItem = computed(() => items.value.find(item => item.workItemId === focusedId.value)
  ?? (focusedItem.value?.workItemId === focusedId.value ? focusedItem.value : undefined))
const error = computed(() => notice.value || tracker.problem.value || (expanded.value ? problem.value : ''))
const project = computed(() => currentItem.value?.projectName)
const saved = ref(false)
const pausedDuration = ref(0)
const displayDuration = computed(() => running.value ? tracker.runningDuration.value : pausedDuration.value)
const toggleDisabled = computed(() => tracker.busy.value || !tracker.current.value)
const canConfigure = computed(() => !!desktop?.setPreferences)
const orbLeft = computed(() => orb.value.canvas?.left ?? (orb.value.side === 'left' ? orb.value.detailWidth : 0))
let savedTimer: ReturnType<typeof setTimeout> | undefined
let lastSavedAt = 0
let disposed = false

function showSaved(at: number) {
  if (!at) { clearTimeout(savedTimer); saved.value = false; lastSavedAt = 0; return }
  if (at <= lastSavedAt || Date.now() - at > 10000) return
  lastSavedAt = at
  clearTimeout(savedTimer)
  saved.value = true
  savedTimer = setTimeout(() => { saved.value = false }, 2600)
}

function focusSearch() { void nextTick(() => search.value?.focus()) }
function hover(value: boolean) {
  pointerInside.value = value
  clearTimeout(hoverTimer); clearTimeout(leaveTimer)
  if (value && dragging.value) return
  if (value) hoverTimer = setTimeout(() => { void openDetails() }, 350)
  else leaveTimer = setTimeout(() => { if (!layoutElement.value?.querySelector(':focus-visible')) closeDetails() }, 250)
}
/** The native shape grows before the capsule renders, and shrinks only after its leave transition. */
async function openDetails() {
  if (!mounted || expanded.value || detailsOpen.value || opening) return
  const revision = ++detailRevision
  opening = true
  await adjustOrb({ details: true })
  if (revision !== detailRevision) return
  opening = false
  if (!disposed && !expanded.value && (orb.value.side || orb.value.dock?.expanded)) detailsOpen.value = true
}
function closeDetails() {
  const pending = opening
  ++detailRevision; opening = false
  if (detailsOpen.value) detailsOpen.value = false
  else if (pending || orb.value.side || orb.value.dock?.expanded) void adjustOrb({ details: false })
}
function releaseDetails() { if (!detailsOpen.value && !opening) void adjustOrb({ details: false }) }
function toggleDetails() { if (detailsOpen.value) closeDetails(); else void openDetails() }
function applyMode(mode: TimerWindowMode) {
  const value = mode !== 'compact'
  if (value) view.value = mode === 'settings' ? 'settings' : 'picker'
  if (expanded.value === value) { if (value && view.value === 'picker') focusSearch(); return }
  clearTimeout(hoverTimer); clearTimeout(leaveTimer); ++detailRevision; opening = false
  detailsOpen.value = false
  orb.value = { ...orb.value, side: null, detailWidth: 0, ...(orb.value.dock ? { dock: { ...orb.value.dock, expanded: false } } : {}) }
  expanded.value = value; emit('mode', value)
  if (value && view.value === 'picker') focusSearch()
}
async function adjustOrb(change: TimerOrbChange) {
  const revision = ++layoutRevision
  if (desktop) {
    try { const layout = await desktop.setOrbLayout(change); if (!disposed && revision === layoutRevision) orb.value = layout }
    catch { notice.value = '悬浮球调整失败，请重试。' }
    return
  }
  const rect = layoutElement.value?.querySelector('.orb-disc')?.getBoundingClientRect()
  const view = layoutElement.value?.ownerDocument.defaultView ?? window
  const popout = view !== window
  const viewportWidth = popout ? view.screen.availWidth : view.innerWidth
  const viewportHeight = popout ? view.screen.availHeight : view.innerHeight
  const size = Math.min(orb.value.size, Math.max(56, viewportWidth - 176), viewportHeight - 16)
  const x = Math.max(8, Math.min((rect?.left ?? viewportWidth - size - 24) + (popout ? view.screenX : 0), viewportWidth - size - 8))
  const left = x - 8, right = viewportWidth - x - size - 8
  const details = change.details ?? orb.value.side !== null
  const side = details ? (left >= right ? 'left' : 'right') : null
  const detailWidth = details ? Math.min(CAPSULE_WIDTH, Math.max(0, left, right)) : 0
  orb.value = { size, side: detailWidth > 0 ? side : null, detailWidth }
  emit('orb', orb.value)
}
async function changeMode(value: boolean, mode: TimerWindowMode = value ? 'picker' : 'compact') {
  if (expanded.value === value && (!value || view.value === mode)) { if (value && mode === 'picker') focusSearch(); return }
  applyMode(mode)
  if (desktop) await desktop.setMode(mode).catch(() => { notice.value = '窗口调整失败，请重试。' })
}
async function syncWindow() {
  if (!desktop) return
  const revision = layoutRevision
  const state = await desktop.getWindowState()
  if (disposed) return
  pinned.value = state.pinned
  if (state.preferences) preferences.value = state.preferences
  if (revision === layoutRevision) {
    orb.value = state.orb
    if (detailsOpen.value && !state.orb.side && !state.orb.dock?.expanded) { ++detailRevision; detailsOpen.value = false }
  }
  hover(state.hovered ?? false)
  showSaved(state.savedAt)
}
const offMode = desktop?.onMode(mode => { applyMode(mode); void syncWindow().catch(() => undefined) })
const offHover = desktop?.onOrbHover(hover)
const offFailed = desktop?.onCommandFailed(() => { notice.value = '托盘操作尚未确认，请核对当前计时后重试。'; void load() })
const offDragging = desktop?.onDragging?.(value => {
  dragging.value = value
  if (!value) return
  clearTimeout(hoverTimer); clearTimeout(leaveTimer)
  if (!orb.value.dock) closeDetails()
})
onMounted(async () => {
  mounted = true
  if (desktop) {
    try {
      const state = await desktop.getWindowState()
      if (disposed) return
      pinned.value = state.pinned
      if (state.preferences) preferences.value = state.preferences
      orb.value = state.orb
      showSaved(state.savedAt)
      applyMode(state.mode)
      hover(state.hovered ?? false)
    } catch { notice.value = '窗口状态暂不可用。' }
  } else if (expanded.value) focusSearch()
})
onBeforeUnmount(() => { disposed = true; clearTimeout(savedTimer); clearTimeout(hoverTimer); clearTimeout(leaveTimer); offMode?.(); offHover?.(); offFailed?.(); offDragging?.() })
watch(() => props.orbLayout, layout => { if (layout && !desktop) orb.value = layout })
watch(candidates, () => { selected.value = 0 })
watch(currentItem, item => { if (item) focusedItem.value = item })
watch(() => props.initialExpanded, value => applyMode(value ? 'picker' : 'compact'))
watch(() => tracker.current.value?.rowVersion, () => { notice.value = '' })
watch(tracker.savedAt, showSaved, { immediate: true })
watch(tracker.runningDuration, value => { if (running.value) pausedDuration.value = value }, { immediate: true, flush: 'sync' })
watch(focusedId, () => { if (!expanded.value && !currentItem.value) void load() })
watch(error, value => { if (value && !expanded.value) void changeMode(true) })

async function begin(id: string) {
  notice.value = ''
  const item = items.value.find(item => item.workItemId === id)
  if (await tracker.start(id)) {
    focusedItem.value = item ?? focusedItem.value; query.value = ''; await changeMode(false)
  }
}
async function pause() { notice.value = ''; await tracker.stop() }
function toggle() {
  if (suppressToggle) { suppressToggle = false; return }
  if (running.value) void pause()
  else if (resume.value) void begin(resume.value.workItemId)
  else void changeMode(true)
}
/**
 * The orb's play/pause control cannot be a native drag region, so a press that travels a few pixels
 * hands the window to the shell to follow the cursor and the click that ends it is ignored.
 */
function pressControl(event: PointerEvent) {
  suppressToggle = false
  const drag = desktop?.dragWindow
  if (!drag || event.button !== 0) return
  const control = event.currentTarget as HTMLElement
  const x = event.screenX, y = event.screenY
  let moving = false
  const move = (next: PointerEvent) => {
    if (moving || next.pointerId !== event.pointerId || Math.hypot(next.screenX - x, next.screenY - y) < 4) return
    moving = true; suppressToggle = true
    void drag(true).catch(() => undefined)
  }
  const end = (next: PointerEvent) => {
    if (next.pointerId !== event.pointerId) return
    control.removeEventListener('pointermove', move); control.removeEventListener('pointerup', end)
    control.removeEventListener('pointercancel', end); control.removeEventListener('lostpointercapture', end)
    if (control.hasPointerCapture?.(event.pointerId)) control.releasePointerCapture(event.pointerId)
    if (moving) void drag(false).catch(() => undefined)
  }
  control.setPointerCapture?.(event.pointerId)
  control.addEventListener('pointermove', move); control.addEventListener('pointerup', end)
  control.addEventListener('pointercancel', end); control.addEventListener('lostpointercapture', end)
}
/** The orb is always dragged on its own, so grabbing it in the page collapses the capsule as the shell does on the desktop. */
function dragOrb(event: PointerEvent) {
  if (!desktop && event.button === 0) { clearTimeout(hoverTimer); closeDetails() }
  emit('drag', event)
}
function retry() {
  notice.value = ''; tracker.problem.value = ''
  void Promise.allSettled([tracker.refresh(), load()])
}
async function pin() {
  if (!desktop) return
  const value = !pinned.value
  try { await desktop.setAlwaysOnTop(value); pinned.value = value }
  catch { notice.value = '置顶设置失败，请重试。' }
}
async function updatePreferences(change: TimerPreferencesChange) {
  if (!desktop?.setPreferences || !preferences.value) return
  const previous = preferences.value
  preferences.value = { ...previous, ...change }
  try { preferences.value = await desktop.setPreferences(change) }
  catch { preferences.value = previous; notice.value = '设置保存失败，请重试。' }
}
function hide() {
  if (desktop) void desktop.hide().catch(() => { notice.value = '隐藏失败，请重试。' })
  else emit('hide')
}
async function openCurrent() {
  const item = running.value ?? resume.value
  if (!item?.projectId || !item.workItemId) return
  if (desktop) await desktop.openWorkItem(item.projectId, item.workItemId)
  else await router.push({ name: 'project-overview', params: { projectId: item.projectId }, query: { workItemId: item.workItemId } })
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    if (query.value) query.value = ''
    else if (expanded.value && view.value === 'settings') void changeMode(true, 'picker')
    else if (expanded.value && (running.value || resume.value)) void changeMode(false)
    else hide()
    return
  }
  if (expanded.value && view.value === 'picker' && (event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault(); focusSearch(); return
  }
  if (event.target !== search.value) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    const count = candidates.value.length
    if (!count) return
    selected.value = (selected.value + (event.key === 'ArrowDown' ? 1 : -1) + count) % count
    results.value?.querySelectorAll('[role="option"]')[selected.value]?.scrollIntoView({ block: 'nearest' })
  } else if (event.key === 'Enter' && !event.isComposing) {
    event.preventDefault()
    const item = candidates.value[selected.value]
    if (item && !loading.value && !tracker.busy.value) void begin(item.workItemId)
  }
}
</script>

<template>
  <section
    class="timer-panel"
    :class="{ 'is-compact': !expanded, 'is-desktop': desktop, 'is-running': running, 'is-saving': tracker.busy.value, 'is-saved': saved && !running, 'is-offline': !tracker.connected.value }"
    :style="expanded ? undefined : { '--orb-width': `${orb.canvas?.width ?? orb.size + orb.detailWidth}px`, '--orb-height': `${orb.canvas?.height ?? orb.size}px`, visibility: desktop && !orb.canvas ? 'hidden' : undefined }"
    aria-label="工作计时器"
    @keydown="keydown"
  >
    <div
      v-if="!expanded"
      ref="layoutElement"
      class="orb-layout"
      @pointerenter="!desktop && hover(true)"
      @pointerleave="!desktop && hover(false)"
      @focusout="!pointerInside && hover(false)"
    >
      <TimerDock
        v-if="orb.dock"
        :side="orb.dock.side"
        :left="orb.canvas?.left ?? 0"
        :top="orb.canvas?.top ?? 0"
        :expanded="detailsOpen"
        :active="pointerInside && !dragging"
        :dragging="dragging"
        :running="!!running"
        :saved="saved && !running"
        :busy="tracker.busy.value"
        :offline="!tracker.connected.value"
        :duration="displayDuration"
        :title="title"
        :project="project"
        :item="currentItem"
        :has-work="!!focusedId"
        :can-resume="!!resume"
        :toggle-disabled="toggleDisabled"
        @toggle="toggle"
        @switch="changeMode(true)"
        @open="openCurrent"
        @closed="releaseDetails"
        @expand="toggleDetails"
      />
      <TimerOrb
        v-else
        :size="orb.size"
        :side="orb.side"
        :detail-width="orb.detailWidth"
        :left="orbLeft"
        :top="orb.canvas?.top ?? 0"
        :open="detailsOpen"
        :active="pointerInside && !dragging"
        :dragging="dragging"
        :running="!!running"
        :saved="saved && !running"
        :busy="tracker.busy.value"
        :offline="!tracker.connected.value"
        :duration="displayDuration"
        :title="title"
        :project="project"
        :has-work="!!focusedId"
        :can-resume="!!resume"
        :toggle-disabled="toggleDisabled"
        @toggle="toggle"
        @switch="changeMode(true)"
        @open="openCurrent"
        @drag="dragOrb"
        @press="pressControl"
        @closed="releaseDetails"
        @reveal="openDetails"
      />
    </div>
    <template v-else>
      <header
        class="panel-header"
        @pointerdown="emit('drag', $event)"
      >
        <template v-if="view === 'settings'">
          <button
            class="icon-button"
            aria-label="返回工作列表"
            @click="changeMode(true, 'picker')"
          >
            <TimerIcon name="back" />
          </button>
          <span class="brand">显示设置</span>
        </template>
        <span
          v-else
          class="brand"
        ><span class="brand-mark"><TimerIcon name="clock" /></span>工作计时</span>
        <div class="window-actions">
          <button
            v-if="canConfigure && view === 'picker'"
            class="icon-button"
            aria-label="计时设置"
            title="计时设置"
            @click="changeMode(true, 'settings')"
          >
            <TimerIcon name="settings" />
          </button>
          <button
            v-if="desktop"
            class="icon-button"
            :class="{ 'is-pinned': pinned }"
            :aria-label="pinned ? '取消置顶' : '置顶浮窗'"
            :aria-pressed="pinned"
            :title="pinned ? '取消置顶' : '置顶浮窗'"
            @click="pin"
          >
            <TimerIcon name="pin" />
          </button>
          <button
            class="icon-button"
            aria-label="收起选择器"
            title="收起选择器"
            @click="changeMode(false)"
          >
            <TimerIcon name="collapse" />
          </button>
        </div>
      </header>
      <p
        v-if="error"
        class="panel-problem"
        role="alert"
      >
        {{ error }} <button
          class="text-button"
          @click="retry"
        >
          重试
        </button>
      </p>
      <TimerSettings
        v-if="view === 'settings' && preferences"
        :preferences="preferences"
        :pinned="pinned"
        @update="updatePreferences"
        @pin="pin"
      />
      <template v-else>
        <div
          v-if="running || (saved && resume)"
          class="focus-card"
          :class="{ 'is-timing': running }"
        >
          <div class="focus-heading">
            <WorkItemGlyph
              v-if="currentItem"
              :code="currentItem.contentCode"
              :name="currentItem.contentName"
              :color-token="currentItem.contentColorToken"
            />
            <div class="work-identity">
              <strong>{{ title }}</strong><span v-if="project">{{ project }}</span>
            </div>
            <button
              v-if="running?.workItemId || (!running && resume)"
              class="icon-button"
              aria-label="打开当前工作项"
              title="打开工作项"
              @click="openCurrent"
            >
              <TimerIcon name="external" />
            </button>
          </div>
          <div class="focus-controls">
            <span
              v-if="saved && !running"
              class="saved-feedback"
              role="status"
            ><TimerIcon name="check" />已保存</span>
            <span
              v-else-if="running"
              class="focus-time"
            ><span
              class="focus-ring"
              aria-hidden="true"
            /><TimerDigits :duration="tracker.runningDuration.value" /></span>
            <button
              class="play-button"
              :class="{ 'is-pause': running }"
              :disabled="tracker.busy.value || !tracker.current.value"
              :aria-label="running ? '暂停计时' : '继续计时'"
              @click="running ? pause() : resume && begin(resume.workItemId)"
            >
              <TimerIcon
                :name="tracker.busy.value ? 'loader' : running ? 'pause' : 'play'"
                :class="{ spinning: tracker.busy.value }"
              /><span>{{ running ? '暂停' : '继续' }}</span>
            </button>
          </div>
        </div>
        <p
          v-if="!error && !tracker.connected.value"
          class="panel-problem is-waiting"
          role="status"
        >
          等待连接
        </p>
        <div class="picker-search">
          <TimerIcon name="search" />
          <input
            ref="search"
            v-model="query"
            type="search"
            role="combobox"
            aria-label="跨项目搜索工作项"
            aria-autocomplete="list"
            aria-controls="timer-options"
            :aria-expanded="true"
            :aria-activedescendant="candidates[selected] ? `timer-option-${candidates[selected]?.workItemId}` : undefined"
            maxlength="200"
            placeholder="搜索工作项或项目"
            autocomplete="off"
          >
          <kbd
            v-if="!query"
            aria-hidden="true"
          >Ctrl K</kbd>
        </div>
        <div
          v-if="!searching"
          class="picker-scope"
        >
          <button
            :class="{ 'is-active': scope === ListTimerCandidatesScopeEnum.Personal }"
            :aria-pressed="scope === ListTimerCandidatesScopeEnum.Personal"
            @click="scope = ListTimerCandidatesScopeEnum.Personal"
          >
            最近与我的
          </button>
          <button
            :class="{ 'is-active': scope === ListTimerCandidatesScopeEnum.All }"
            :aria-pressed="scope === ListTimerCandidatesScopeEnum.All"
            @click="scope = ListTimerCandidatesScopeEnum.All"
          >
            全部
          </button>
        </div>
        <div
          ref="results"
          class="picker-results"
          :aria-busy="loading"
        >
          <div
            id="timer-options"
            role="listbox"
            aria-label="可计时工作项"
          >
            <button
              v-for="(item, index) in candidates"
              :id="`timer-option-${item.workItemId}`"
              :key="item.workItemId"
              class="candidate"
              :class="{ 'is-selected': selected === index }"
              role="option"
              :aria-selected="selected === index"
              :disabled="tracker.busy.value || loading"
              :aria-label="`${running ? '切换到' : item.lastTrackedAt ? '继续' : '开始'} ${item.title} · ${item.projectName} · ${item.itemNo}`"
              @mouseenter="selected = index"
              @click="begin(item.workItemId)"
            >
              <WorkItemGlyph
                :code="item.contentCode"
                :name="item.contentName"
                :color-token="item.contentColorToken"
              />
              <span class="candidate-copy"><strong>{{ item.title }}</strong><span class="candidate-context">{{ item.projectName }}</span></span>
              <TimerIcon
                name="play"
                class="candidate-action"
              />
            </button>
          </div>
          <div
            v-if="loading"
            class="empty-state"
            role="status"
          >
            <TimerIcon
              name="loader"
              class="spinning"
            />
          </div>
          <div
            v-else-if="!candidates.length && !problem"
            class="empty-state"
          >
            <TimerIcon name="search" /><span>{{ searching ? '没有匹配的工作项' : '暂无工作项' }}</span><button
              v-if="!searching && scope !== ListTimerCandidatesScopeEnum.All"
              class="text-button"
              @click="scope = ListTimerCandidatesScopeEnum.All"
            >
              查看全部
            </button>
          </div>
          <button
            v-if="nextOffset !== null && !loading"
            class="load-more"
            @click="load(true)"
          >
            加载更多
          </button>
        </div>
      </template>
    </template>
  </section>
</template>

<style scoped>
.timer-panel{box-sizing:border-box;display:flex;flex-direction:column;width:100%;height:480px;max-height:100dvh;overflow:hidden;background:var(--yp-timer-surface);color:var(--yp-text-primary);font-family:var(--yp-font-family);font-size:13px;border:1px solid var(--yp-timer-hairline);border-radius:16px;isolation:isolate;box-shadow:var(--yp-timer-highlight)}
.timer-panel.is-desktop:not(.is-compact){width:calc(100vw - 24px);height:calc(100vh - 24px);max-height:none;margin:12px;box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight)}
.timer-panel.is-compact{width:var(--orb-width);height:var(--orb-height);max-height:none;background:transparent;border:0;box-shadow:none;overflow:visible;border-radius:0}
.orb-layout{position:relative;width:var(--orb-width);height:var(--orb-height)}
button,input{font:inherit}
button{cursor:pointer;color:inherit;transition:transform .18s ease,background .18s ease,color .18s ease,box-shadow .18s ease}
button:disabled{cursor:default;opacity:.6}
button:not(:disabled):active{transform:scale(.94)}
button:focus-visible,input:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:2px}
.panel-header{display:flex;align-items:center;gap:8px;min-height:48px;padding:0 10px 0 14px;flex-shrink:0;user-select:none;touch-action:none}
.is-desktop .panel-header{-webkit-app-region:drag;app-region:drag}
.is-desktop button,.is-desktop input{-webkit-app-region:no-drag;app-region:no-drag}
.brand{display:flex;align-items:center;gap:9px;font-size:13px;font-weight:650;white-space:nowrap;letter-spacing:-.01em}
.brand-mark{display:grid;place-items:center;width:24px;height:24px;border-radius:8px;color:#fff;background:linear-gradient(145deg,color-mix(in srgb,var(--yp-timer-accent) 82%,#fff),var(--yp-timer-accent));box-shadow:0 2px 6px -1px color-mix(in srgb,var(--yp-timer-accent) 45%,transparent)}
.brand-mark svg{width:14px;height:14px}
.window-actions{margin-left:auto;display:flex;gap:2px}
.icon-button{width:28px;height:28px;display:grid;place-items:center;padding:6px;border:0;border-radius:8px;background:transparent;color:var(--yp-text-muted)}
.icon-button svg{width:15px;height:15px}
.icon-button:hover{background:var(--yp-timer-hover);color:var(--yp-text-primary)}
.icon-button.is-pinned{color:var(--yp-timer-accent)}
.panel-header>.icon-button{margin-left:-6px}
.focus-card{margin:2px 12px 12px;padding:12px 12px 12px 14px;border-radius:13px;background:var(--yp-timer-sunken);box-shadow:inset 0 0 0 1px var(--yp-timer-hairline);flex-shrink:0}
.focus-card.is-timing{background:linear-gradient(135deg,color-mix(in srgb,var(--yp-timer-accent) 10%,var(--yp-bg-raised)),color-mix(in srgb,var(--yp-timer-accent) 3%,var(--yp-bg-raised)));box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--yp-timer-accent) 18%,transparent)}
.focus-heading{display:flex;gap:9px;align-items:center}
.focus-heading .work-glyph{width:30px;height:32px}
.work-identity{display:flex;flex-direction:column;gap:3px;min-width:0;flex:1}
.work-identity strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;font-weight:650}
.work-identity>span{font-size:11px;color:var(--yp-text-muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.focus-controls{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:10px}
.focus-time{display:flex;align-items:center;gap:10px}
.focus-ring{position:relative;width:14px;height:14px;border-radius:50%;box-shadow:inset 0 0 0 2px color-mix(in srgb,var(--yp-timer-accent) 22%,transparent)}
.focus-ring::after{content:'';position:absolute;inset:0;border-radius:50%;border:2px solid transparent;border-top-color:var(--yp-timer-accent);animation:orb-spin 2.4s linear infinite}
.focus-controls .timer-digits{font-size:28px;font-weight:600;color:var(--yp-text-primary)}
.play-button{display:flex;align-items:center;gap:6px;height:34px;padding:0 14px 0 12px;background:var(--yp-timer-accent);color:#fff;border:0;border-radius:999px;font-size:12px;font-weight:600;box-shadow:0 3px 10px -2px color-mix(in srgb,var(--yp-timer-accent) 45%,transparent)}
.play-button svg{width:14px;height:14px}
.play-button:not(:disabled):hover{transform:translateY(-1px)}
.play-button.is-pause{background:var(--yp-bg-raised);color:var(--yp-timer-accent);box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--yp-timer-accent) 26%,transparent)}
.picker-search{display:flex;align-items:center;gap:9px;margin:0 12px 4px;padding:0 10px 0 12px;height:38px;flex-shrink:0;border:1px solid var(--yp-timer-hairline);border-radius:10px;background:var(--yp-bg-raised);color:var(--yp-text-muted);transition:box-shadow .2s,border-color .2s}
.picker-search:focus-within{border-color:var(--yp-timer-accent);box-shadow:0 0 0 3px color-mix(in srgb,var(--yp-timer-accent) 12%,transparent)}
.picker-search svg{width:15px;height:15px;flex-shrink:0}
.picker-search input{width:100%;min-width:0;height:100%;background:transparent;border:0;outline:none;color:var(--yp-text-primary);font-size:12.5px}
.picker-search input:focus-visible{outline:none}
.picker-search input::placeholder{color:var(--yp-text-muted)}
.picker-search kbd{flex-shrink:0;padding:1px 6px;border-radius:5px;font:500 10px/16px var(--yp-font-family);color:var(--yp-text-muted);background:var(--yp-timer-sunken);box-shadow:inset 0 0 0 1px var(--yp-timer-hairline)}
.picker-scope{display:flex;align-self:flex-start;margin:8px 12px 4px;padding:2px;border-radius:9px;background:var(--yp-timer-sunken);box-shadow:inset 0 0 0 1px var(--yp-timer-hairline);flex-shrink:0}
.picker-scope button{border:0;border-radius:7px;background:transparent;padding:4px 12px;font-size:11.5px;color:var(--yp-text-secondary)}
.picker-scope button.is-active{background:var(--yp-timer-chip);color:var(--yp-text-primary);font-weight:600;box-shadow:var(--yp-timer-shadow-soft)}
.picker-results{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain;scrollbar-width:thin;padding:4px 8px 10px}
.candidate{position:relative;box-sizing:border-box;display:flex;align-items:center;gap:10px;width:100%;padding:9px 10px;text-align:left;border:0;border-radius:10px;background:transparent;margin:1px 0;animation:candidate-in .24s ease both}
.candidate::before{content:'';position:absolute;left:0;top:10px;bottom:10px;width:3px;border-radius:0 3px 3px 0;background:var(--yp-timer-accent);opacity:0;transform:scaleY(.4);transition:opacity .16s ease,transform .2s var(--yp-timer-ease)}
.candidate.is-selected,.candidate:hover{background:var(--yp-timer-hover)}
.candidate.is-selected::before{opacity:1;transform:none}
.candidate-copy{display:flex;flex-direction:column;gap:4px;min-width:0;flex:1}
.candidate-copy strong{font-size:12.5px;font-weight:600;line-height:18px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}
.candidate-context{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:11px;line-height:15px;color:var(--yp-text-muted)}
.candidate-action{width:24px;height:24px;padding:6px;box-sizing:border-box;border-radius:50%;flex-shrink:0;color:#fff;background:var(--yp-timer-accent);opacity:0;transform:scale(.8);transition:opacity .18s,transform .18s}
.candidate:hover .candidate-action,.candidate.is-selected .candidate-action,.candidate:focus-visible .candidate-action{opacity:1;transform:none}
.empty-state{min-height:135px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;padding:14px;color:var(--yp-text-muted);font-size:12px;text-align:center}
.empty-state>svg{width:24px;height:24px;opacity:.6}
.text-button{color:var(--yp-timer-accent);border:0;background:transparent;padding:3px;font-size:12px}
.load-more{display:block;width:100%;padding:10px;background:transparent;border:0;color:var(--yp-timer-accent);font-size:12px}
.panel-problem{margin:0 12px 10px;padding:7px 10px;font-size:11px;color:var(--yp-status-red);background:color-mix(in srgb,var(--yp-status-red) 8%,var(--yp-bg-raised));border-radius:8px}
.panel-problem.is-waiting{color:var(--yp-text-secondary);background:var(--yp-timer-sunken)}
.saved-feedback{display:flex;align-items:center;gap:7px;color:var(--yp-timer-saved);font-size:13px;font-weight:600}
.saved-feedback svg{width:22px;height:22px;stroke-dasharray:36;animation:saved-check .42s ease both}
.spinning{animation:orb-spin 1s linear infinite}
@keyframes orb-spin{to{transform:rotate(360deg)}}
@keyframes saved-check{from{stroke-dashoffset:36;transform:scale(.65)}to{stroke-dashoffset:0;transform:scale(1)}}
@keyframes candidate-in{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
</style>
