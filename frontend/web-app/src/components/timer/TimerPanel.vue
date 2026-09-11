<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { TimerOrbChange, TimerOrbLayout } from '@yumpoo/preload-contract'
import { ListTimerCandidatesScopeEnum, type TimerCandidate } from '@yumpoo/api-client'
import { useTimeTracker } from '../../composables/useTimeTracker'
import { useTimerCandidates } from '../../composables/useTimerCandidates'
import TimerIcon from './TimerIcon.vue'
import TimerDigits from './TimerDigits.vue'
import WorkItemGlyph from './WorkItemGlyph.vue'

const props = withDefaults(defineProps<{ initialExpanded?: boolean; floating?: boolean; orbLayout?: TimerOrbLayout }>(), { initialExpanded: true, floating: false, orbLayout: () => ({ size: 176, side: null, detailWidth: 0 }) })
const emit = defineEmits<{ hide: []; mode: [expanded: boolean]; drag: [event: PointerEvent]; orb: [layout: TimerOrbLayout] }>()
const desktop = window.yumpooDesktop?.timer
const router = useRouter()
const tracker = useTimeTracker()
const expanded = ref(props.initialExpanded)
const orb = ref<TimerOrbLayout>(props.orbLayout ?? { size: 176, side: null, detailWidth: 0 })
const orbElement = ref<HTMLElement>()
const layoutElement = ref<HTMLElement>()
const controlsVisible = ref(false)
const pointerInside = ref(false)
const startedTitle = ref('')
const titleShown = ref(false)
let titleRevision = 0
let titleOpening = false
let layoutRevision = 0
let hoverTimer: ReturnType<typeof setTimeout> | undefined
let leaveTimer: ReturnType<typeof setTimeout> | undefined
let mounted = false
const resizing = ref(false)
let endResize: (() => void) | undefined
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
  if (value) hoverTimer = setTimeout(() => { controlsVisible.value = true; void showTitle() }, 350)
  else {
    hideTitle()
    leaveTimer = setTimeout(() => {
    if (resizing.value || layoutElement.value?.querySelector(':focus-visible')) return
    controlsVisible.value = false
    if (orb.value.side) void adjustOrb({ details: false })
    }, 250)
  }
}
async function showTitle() {
  if (!mounted || expanded.value || !running.value || !pointerInside.value || !controlsVisible.value) return
  if ((titleShown.value || titleOpening) && startedTitle.value === title.value) return
  const revision = ++titleRevision
  titleOpening = true
  startedTitle.value = title.value ?? ''
  await adjustOrb({ titleVisible: true })
  await nextTick()
  if (disposed || revision !== titleRevision || expanded.value) return
  titleOpening = false
  titleShown.value = true
}
function hideTitle() {
  ++titleRevision
  titleOpening = false
  const wasShown = titleShown.value
  titleShown.value = false
  if (!wasShown && (orb.value.titleVisible || startedTitle.value)) void releaseTitle()
}
async function releaseTitle() {
  if (titleShown.value) return
  startedTitle.value = ''
  await adjustOrb({ titleVisible: false })
}
function applyMode(value: boolean) {
  if (expanded.value === value) { if (value) focusSearch(); return }
  endResize?.(); hideTitle(); startedTitle.value = ''; orb.value = { ...orb.value, side: null, detailWidth: 0, titleVisible: false }
  expanded.value = value; emit('mode', value); if (value) focusSearch()
  if (!desktop) void nextTick(showTitle)
}
async function adjustOrb(change: TimerOrbChange) {
  const revision = ++layoutRevision
  if (desktop) {
    try { const layout = await desktop.setOrbLayout(change); if (!disposed && revision === layoutRevision) orb.value = layout }
    catch { notice.value = '悬浮球调整失败，请重试。' }
    return
  }
  const rect = orbElement.value?.getBoundingClientRect()
  const view = orbElement.value?.ownerDocument.defaultView ?? window
  const popout = view !== window
  const viewportWidth = popout ? view.screen.availWidth : view.innerWidth
  const viewportHeight = popout ? view.screen.availHeight : view.innerHeight
  const titleVisible = change.titleVisible ?? orb.value.titleVisible ?? false
  const size = Math.min(change.size ?? orb.value.size, Math.max(128, viewportWidth - 176), viewportHeight - 16 - (titleVisible ? 40 : 0))
  const x = Math.max(8, Math.min((rect?.left ?? viewportWidth - size - 24) + (popout ? view.screenX : 0), viewportWidth - size - 8))
  const left = x - 8, right = viewportWidth - x - size - 8
  const details = change.details ?? orb.value.side !== null
  const side = details ? (left >= right ? 'left' : 'right') : null
  const detailWidth = details ? Math.min(224, Math.max(0, viewportWidth - size - 16)) : 0
  orb.value = { size, side: detailWidth > 0 ? side : null, detailWidth, ...(titleVisible ? { titleVisible: true } : {}) }
  emit('orb', orb.value)
}
function startResize(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault(); event.stopPropagation(); endResize?.()
  const handle = event.currentTarget as HTMLElement
  const view = handle.ownerDocument.defaultView ?? window
  const startSize = orb.value.size, x = event.screenX, y = event.screenY
  let frame = 0, nextSize = startSize
  const apply = () => { frame = 0; void adjustOrb({ size: nextSize }) }
  const move = (next: PointerEvent) => {
    if (next.pointerId !== event.pointerId) return
    nextSize = Math.max(128, Math.min(280, Math.round(startSize + (next.screenX - x - next.screenY + y) / 2)))
    if (!frame) frame = view.requestAnimationFrame(apply)
  }
  const stop = (next: PointerEvent) => { if (next.pointerId === event.pointerId) endResize?.() }
  endResize = () => {
    if (frame) { view.cancelAnimationFrame(frame); if (!disposed) apply() }
    resizing.value = false
    handle.removeEventListener('pointermove', move); handle.removeEventListener('pointerup', stop)
    handle.removeEventListener('pointercancel', stop); handle.removeEventListener('lostpointercapture', stop)
    view.removeEventListener('blur', endResize!)
    if (handle.hasPointerCapture?.(event.pointerId)) handle.releasePointerCapture(event.pointerId)
    endResize = undefined
    if (!pointerInside.value) hover(false)
  }
  resizing.value = true
  handle.setPointerCapture?.(event.pointerId)
  handle.addEventListener('pointermove', move); handle.addEventListener('pointerup', stop)
  handle.addEventListener('pointercancel', stop); handle.addEventListener('lostpointercapture', stop)
  view.addEventListener('blur', endResize)
}
function resizeKey(event: KeyboardEvent) {
  const delta = ['ArrowRight', 'ArrowUp'].includes(event.key) ? 8 : ['ArrowLeft', 'ArrowDown'].includes(event.key) ? -8 : 0
  if (!delta) return
  event.preventDefault(); void adjustOrb({ size: Math.max(128, Math.min(280, orb.value.size + delta)) })
}
async function changeMode(value: boolean) {
  if (expanded.value === value) { showTitle(); return }
  applyMode(value)
  if (desktop) await desktop.setMode(value ? 'picker' : 'compact').catch(() => { notice.value = '窗口调整失败，请重试。' })
  showTitle()
}
async function syncWindow() {
  if (!desktop) return
  const revision = layoutRevision
  const state = await desktop.getWindowState()
  if (disposed) return
  pinned.value = state.pinned
  if (revision === layoutRevision) orb.value = state.orb
  hover(state.hovered ?? false)
  showSaved(state.savedAt)
  showTitle()
}
const offMode = desktop?.onMode(mode => { applyMode(mode === 'picker'); void syncWindow().catch(() => undefined) })
const offHover = desktop?.onOrbHover(hover)
const offFailed = desktop?.onCommandFailed(() => { notice.value = '托盘操作尚未确认，请核对当前计时后重试。'; void load() })
onMounted(async () => {
  mounted = true
  if (desktop) {
    try {
      const state = await desktop.getWindowState()
      if (disposed) return
      pinned.value = state.pinned
      orb.value = state.orb
      showSaved(state.savedAt)
      applyMode(state.mode === 'picker')
      hover(state.hovered ?? false)
      showTitle()
    } catch { notice.value = '窗口状态暂不可用。' }
  } else if (expanded.value) focusSearch()
  else showTitle()
})
onBeforeUnmount(() => { disposed = true; endResize?.(); clearTimeout(savedTimer); clearTimeout(hoverTimer); clearTimeout(leaveTimer); offMode?.(); offHover?.(); offFailed?.() })
watch(() => props.orbLayout, layout => { if (layout && !desktop) orb.value = layout })
watch(candidates, () => { selected.value = 0 })
watch(currentItem, item => { if (item) focusedItem.value = item })
watch(() => props.initialExpanded, value => applyMode(value))
watch(() => tracker.current.value?.rowVersion, () => { notice.value = '' })
watch(tracker.savedAt, showSaved, { immediate: true })
watch(tracker.runningDuration, value => { if (running.value) pausedDuration.value = value }, { immediate: true, flush: 'sync' })
watch([() => running.value?.id, title], () => {
  if (!running.value) hideTitle()
  else void showTitle()
}, { immediate: true })
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
    else if (expanded.value && (running.value || resume.value)) void changeMode(false)
    else hide()
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
    :style="{ '--orb-size': `${orb.size}px`, '--orb-head': `${orb.canvas?.top ?? (orb.titleVisible ? 40 : 0)}px`, '--orb-left': `${orb.canvas?.left ?? 0}px`, '--orb-height': `${orb.canvas?.height ?? (orb.size + (orb.titleVisible ? 40 : 0))}px`, '--orb-scale': orb.size / 176, '--orb-width': `${orb.canvas?.width ?? (orb.size + orb.detailWidth)}px`, '--detail-width': `${orb.detailWidth}px`, '--detail-lines': orb.size < 152 ? 1 : 2, '--detail-height': `${Math.min(112, orb.size - 24)}px`, visibility: desktop && !expanded && !orb.canvas ? 'hidden' : undefined }"
    aria-label="工作计时器"
    @keydown="keydown"
  >
    <div
      v-if="!expanded"
      ref="layoutElement"
      class="orb-layout"
      :class="[`details-${orb.side ?? 'closed'}`, { 'is-resizing': resizing, 'show-controls': controlsVisible }]"
      @pointerenter="!desktop && hover(true)"
      @pointerleave="!desktop && hover(false)"
      @focusout="!pointerInside && hover(false)"
    >
      <Transition
        name="orb-title"
        @after-leave="releaseTitle"
      >
        <div
          v-if="titleShown && startedTitle"
          class="orb-start-title"
          role="status"
        >
          <span
            class="orb-title-dot"
            aria-hidden="true"
          />
          <span class="orb-title-text">{{ startedTitle }}</span>
        </div>
      </Transition>
      <div
        v-if="orb.side"
        class="orb-details"
      >
        <span class="orb-detail-project">{{ project || '当前工作' }}</span>
        <button
          class="orb-detail-title"
          :disabled="!focusedId"
          @click="openCurrent"
        >
          {{ title || '尚未选择工作项' }}
        </button>
        <div class="orb-detail-actions">
          <button
            class="orb-switch"
            aria-label="查找工作项"
            @click="changeMode(true)"
          >
            <TimerIcon name="search" />更换工作
          </button>
        </div>
      </div>
      <div
        ref="orbElement"
        class="timer-orb"
        @pointerdown="emit('drag', $event)"
      >
        <div class="orb-visual">
          <div
            class="orb-orbit"
            aria-hidden="true"
          />
          <div class="orb-core">
            <TimerDigits :duration="displayDuration" />
            <button
              class="orb-toggle"
              :disabled="tracker.busy.value || !tracker.current.value"
              :aria-label="running ? '暂停计时' : resume ? '继续计时' : '查找工作项'"
              @click="running ? pause() : resume ? begin(resume.workItemId) : changeMode(true)"
            >
              <TimerIcon
                :name="tracker.busy.value ? 'loader' : running ? 'pause' : 'play'"
                :class="{ spinning: tracker.busy.value }"
              />
            </button>
            <Transition
              name="orb-state"
              mode="out-in"
            >
              <span
                v-if="saved && !running"
                key="saved"
                class="orb-save saved-feedback"
                role="status"
              ><TimerIcon name="check" /><span>已保存</span></span>
            </Transition>
            <span
              v-if="!tracker.connected.value"
              class="orb-connection"
              role="status"
            >等待连接</span>
          </div>
          <button
            class="orb-action orb-resize"
            aria-label="调整悬浮球大小"
            @pointerdown="startResize"
            @keydown="resizeKey"
          >
            <TimerIcon name="resize" />
          </button>
          <button
            class="orb-action orb-expand"
            :aria-label="orb.side ? '收起工作详情' : '展开工作详情'"
            :aria-expanded="!!orb.side"
            @click="adjustOrb({ details: !orb.side })"
          >
            <TimerIcon name="details" />
          </button>
        </div>
      </div>
    </div>
    <template v-else>
      <header
        class="panel-header"
        @pointerdown="emit('drag', $event)"
      >
        <span class="brand"><TimerIcon name="clock" />工作计时</span>
        <div class="window-actions">
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
          <TimerDigits
            v-else-if="running"
            :duration="tracker.runningDuration.value"
          />
          <button
            class="play-button"
            :class="{ 'is-pause': running }"
            :disabled="tracker.busy.value || !tracker.current.value"
            :aria-label="running ? '暂停计时' : '继续计时'"
            :title="running ? '暂停计时' : '继续计时'"
            @click="running ? pause() : resume && begin(resume.workItemId)"
          >
            <TimerIcon
              :name="tracker.busy.value ? 'loader' : running ? 'pause' : 'play'"
              :class="{ spinning: tracker.busy.value }"
            />
          </button>
        </div>
      </div>
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
      <p
        v-else-if="!tracker.connected.value"
        class="panel-problem"
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
              name="arrow"
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
  </section>
</template>

<style scoped>
.timer-panel{box-sizing:border-box;display:flex;flex-direction:column;width:100%;height:520px;max-height:100dvh;overflow:hidden;background:var(--yp-bg-surface,#fff);color:var(--yp-text-primary,#25272c);font-family:var(--yp-font-family);font-size:13px;border:1px solid var(--yp-border-subtle,#e6e9ef);border-radius:16px;isolation:isolate}
.timer-panel.is-compact{width:var(--orb-width);height:var(--orb-height);background:transparent;border:0;overflow:visible;border-radius:50%}
button,input{font:inherit}button{cursor:pointer;color:inherit;transition:transform .18s ease,background .18s ease,color .18s ease,box-shadow .18s ease}button:disabled{cursor:default;opacity:.6}button:not(:disabled):active{transform:scale(.92)}button:focus-visible,input:focus-visible{outline:2px solid var(--yp-action-primary,#0073ea);outline-offset:3px}
.panel-header{display:flex;align-items:center;gap:10px;min-height:48px;padding:2px 12px 0 17px;flex-shrink:0;user-select:none;touch-action:none}
.is-desktop .panel-header,.is-desktop .timer-orb{-webkit-app-region:drag;app-region:drag}.is-desktop button,.is-desktop input{-webkit-app-region:no-drag;app-region:no-drag}
.brand{display:flex;align-items:center;gap:8px;font-size:13px;font-weight:650;white-space:nowrap}.brand svg{width:17px;height:17px;color:var(--yp-action-primary)}.window-actions{margin-left:auto;display:flex;gap:2px}.icon-button{width:28px;height:28px;display:grid;place-items:center;padding:6px;border:0;border-radius:8px;background:transparent;color:var(--yp-text-muted)}.icon-button svg{width:15px;height:15px}.icon-button:hover{background:var(--yp-bg-hover)}.icon-button.is-pinned{color:var(--yp-action-primary)}
.focus-card{margin:4px 14px 14px;padding:12px;border:1px solid var(--yp-border-subtle);border-radius:12px;background:var(--yp-bg-sunken);flex-shrink:0}.focus-card.is-timing{background:color-mix(in srgb,var(--yp-action-primary) 4%,var(--yp-bg-surface));border-color:color-mix(in srgb,var(--yp-action-primary) 16%,var(--yp-border-subtle))}.focus-heading{display:flex;gap:8px;align-items:center}.focus-heading .work-glyph{width:30px;height:32px}.work-identity{display:flex;flex-direction:column;gap:4px;min-width:0;flex:1}.work-identity strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;font-weight:650}.work-identity>span{font-size:11px;color:var(--yp-text-secondary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.focus-controls{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:10px;padding-left:3px}.focus-controls .timer-digits{font-size:29px;font-weight:600}.play-button{width:36px;height:36px;display:grid;place-items:center;background:var(--yp-action-primary);color:#fff;border:0;border-radius:50%;padding:8px}.play-button:hover{box-shadow:0 4px 12px color-mix(in srgb,var(--yp-action-primary) 25%,transparent);transform:translateY(-2px)}.play-button.is-pause{background:var(--yp-bg-surface);color:var(--yp-action-primary);box-shadow:0 0 0 1px color-mix(in srgb,var(--yp-action-primary) 24%,transparent)}
.picker-search{display:flex;align-items:center;gap:9px;margin:0 14px 4px;padding:0 12px;height:40px;flex-shrink:0;border:1px solid var(--yp-border-default);border-radius:10px;background:var(--yp-bg-sunken);color:var(--yp-text-muted);transition:box-shadow .2s,border-color .2s}.picker-search:focus-within{border-color:var(--yp-action-primary);box-shadow:0 0 0 3px color-mix(in srgb,var(--yp-action-primary) 9%,transparent)}.picker-search svg{width:16px;height:16px}.picker-search input{width:100%;min-width:0;height:100%;background:transparent;border:0;outline:none;color:var(--yp-text-primary);font-size:12px}.picker-search input:focus-visible{outline:none}.picker-search input::placeholder{color:var(--yp-text-muted)}
.picker-scope{display:flex;align-items:center;gap:4px;margin:9px 14px 5px;flex-shrink:0}.picker-scope button{border:0;border-radius:7px;background:transparent;padding:6px 9px;font-size:11px;color:var(--yp-text-muted)}.picker-scope button.is-active{background:var(--yp-bg-selected);color:var(--yp-action-primary);font-weight:600}
.picker-results{flex:1;min-height:0;overflow:auto;overscroll-behavior:contain;scrollbar-width:thin;padding:4px 8px 10px}.candidate{box-sizing:border-box;display:flex;align-items:center;gap:10px;width:100%;padding:10px 9px;text-align:left;border:1px solid transparent;border-radius:10px;background:transparent;margin:2px 0;animation:candidate-in .24s ease both}.candidate.is-selected,.candidate:hover{background:var(--yp-bg-sunken)}.candidate-copy{display:flex;flex-direction:column;gap:5px;min-width:0;flex:1}.candidate-copy strong{font-size:12px;font-weight:600;line-height:18px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.candidate-context{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:11px;line-height:15px;color:var(--yp-text-secondary)}.candidate-action{width:17px;height:17px;flex-shrink:0;color:var(--yp-action-primary);opacity:0;transform:translateX(-4px);transition:opacity .18s,transform .18s}.candidate:hover .candidate-action,.candidate.is-selected .candidate-action,.candidate:focus-visible .candidate-action{opacity:1;transform:translateX(0)}
.empty-state{min-height:135px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;padding:14px;color:var(--yp-text-muted);font-size:12px;text-align:center}.empty-state>svg{width:24px;height:24px;opacity:.6}.text-button{color:var(--yp-action-primary);border:0;background:transparent;padding:3px;font-size:12px}.load-more{display:block;width:100%;padding:10px;background:transparent;border:0;color:var(--yp-action-primary);font-size:12px}.panel-problem{margin:0 14px 10px;padding:7px 9px;font-size:11px;color:var(--yp-status-red);background:var(--yp-bg-sunken);border-radius:8px}
.timer-orb{position:relative;width:var(--orb-size);height:var(--orb-size);flex:none;user-select:none;touch-action:none;cursor:grab;--orb-accent:var(--yp-action-primary,#0073ea)}.timer-orb:active{cursor:grabbing}
.orb-layout{position:relative;display:flex;align-items:center;width:var(--orb-width);height:var(--orb-height);padding-top:var(--orb-head);box-sizing:border-box}.orb-layout.details-left{flex-direction:row-reverse}.orb-visual{position:absolute;width:176px;height:176px;transform:scale(var(--orb-scale));transform-origin:top left}
.orb-orbit{position:absolute;inset:17px;border:1px solid var(--yp-border-subtle);border-radius:50%;opacity:.65;pointer-events:none}.orb-orbit::after{content:'';position:absolute;inset:-1px;border:1.5px solid transparent;border-top-color:var(--orb-accent);border-right-color:color-mix(in srgb,var(--orb-accent) 25%,transparent);border-radius:50%;opacity:0;transition:opacity .3s}.is-running .orb-orbit::after{opacity:.85;animation:orb-spin 18s linear infinite}.is-running .orb-orbit{opacity:1}
.orb-core{position:absolute;inset:23px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:9px;box-sizing:border-box;border:1px solid var(--yp-border-subtle);border-radius:50%;background:var(--yp-bg-surface);box-shadow:0 6px 18px color-mix(in srgb,#192b46 11%,transparent),inset 0 1px 0 color-mix(in srgb,#fff 55%,transparent);padding:16px 8px;color:var(--yp-text-primary);transition:box-shadow .25s,border-color .25s}.timer-orb:hover .orb-core{box-shadow:0 8px 22px color-mix(in srgb,#192b46 17%,transparent);border-color:color-mix(in srgb,var(--orb-accent) 22%,var(--yp-border-subtle))}.orb-core>.timer-digits{font-size:24px;font-weight:550;color:var(--yp-text-primary);pointer-events:none}.orb-toggle{width:32px;height:32px;display:grid;place-items:center;padding:8px;border:0;border-radius:50%;background:var(--orb-accent);color:#fff;box-shadow:0 2px 5px color-mix(in srgb,var(--orb-accent) 15%,transparent)}.orb-toggle:hover{box-shadow:0 3px 9px color-mix(in srgb,var(--orb-accent) 28%,transparent);transform:translateY(-1px)}.is-running .orb-toggle{background:color-mix(in srgb,var(--orb-accent) 9%,var(--yp-bg-surface));color:var(--orb-accent);box-shadow:none}.is-running .orb-toggle:hover{background:color-mix(in srgb,var(--orb-accent) 16%,var(--yp-bg-surface))}.orb-toggle svg{width:16px;height:16px}.orb-save{position:absolute;bottom:11px;font-size:10px!important;gap:3px!important;pointer-events:none}.orb-save svg{width:12px!important;height:12px!important}.orb-connection{position:absolute;top:12px;font-size:9px;line-height:10px;color:var(--yp-status-orange,#b88230)}
.orb-action{position:absolute;display:grid;place-items:center;width:26px;height:26px;padding:5px;border:0;border-radius:50%;background:var(--yp-bg-surface);color:var(--yp-text-muted);opacity:0;visibility:hidden;transform:scale(.85);transition:transform .18s ease,opacity .18s,background .18s,visibility .18s;box-shadow:0 1px 4px color-mix(in srgb,#192b46 9%,transparent)}.orb-action svg{width:15px;height:15px}.orb-resize{right:27px;top:18px;cursor:nesw-resize;touch-action:none}.orb-expand{left:9px;top:75px}.details-right .orb-expand{left:auto;right:9px}.details-right .orb-expand svg,.details-left .orb-expand svg{transform:rotate(180deg)}.details-right .orb-expand svg{transform:none}.show-controls .orb-action,.timer-orb:has(:focus-visible) .orb-action,.is-resizing .orb-action{opacity:1;visibility:visible;transform:none}.orb-action:hover{background:var(--yp-bg-hover);color:var(--orb-accent)}.orb-action:not(:disabled):active{transform:scale(.9)}
.orb-details{box-sizing:border-box;position:absolute;left:var(--orb-size);right:0;top:calc(var(--orb-head) + (var(--orb-size) - var(--detail-height))/2);height:var(--detail-height);display:flex;flex-direction:column;justify-content:center;gap:4px;margin:0 8px 0 0;padding:clamp(8px,calc((var(--orb-size) - 128px)/12 + 8px),12px) 14px;border:1px solid var(--yp-border-subtle);border-radius:14px;background:var(--yp-bg-surface);box-shadow:0 3px 12px color-mix(in srgb,#192b46 9%,transparent);animation:detail-in .2s ease both;min-width:0}.details-left .orb-details{left:0;right:var(--orb-size);margin:0 0 0 8px}.orb-detail-project{font-size:11px;line-height:16px;color:var(--yp-text-secondary);white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex-shrink:0}.orb-detail-title{display:-webkit-box;-webkit-line-clamp:var(--detail-lines);-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;min-height:0;padding:0;background:none;border:0;font-size:13px;font-weight:600;line-height:20px;text-align:left;color:var(--yp-text-primary)}.orb-detail-title:hover{color:var(--yp-action-primary)}.orb-detail-actions{display:flex;align-items:center;justify-content:space-between;gap:4px;flex-shrink:0;margin-top:3px;padding-top:6px;border-top:1px solid var(--yp-border-subtle)}.orb-switch{display:flex;align-items:center;gap:5px;border:0;background:none;padding:3px 0;color:var(--yp-action-primary);font-size:11px;white-space:nowrap}.orb-switch svg{width:13px;height:13px}.orb-detail-tools{display:flex;margin-right:-5px}.orb-detail-tools .icon-button{width:24px;height:24px;padding:5px}.orb-detail-tools svg{width:13px;height:13px}
.saved-feedback{display:flex;align-items:center;gap:7px;color:var(--yp-status-green,#25884d);font-size:13px;font-weight:600}.saved-feedback svg{width:22px;height:22px;stroke-dasharray:36;animation:saved-check .42s ease both}.is-saved .timer-orb{--orb-accent:var(--yp-status-green,#25884d)}.is-saved .orb-orbit{border-color:var(--orb-accent);animation:saved-ring .6s ease both}.spinning{animation:orb-spin 1s linear infinite}.orb-state-enter-active,.orb-state-leave-active{transition:opacity .16s ease,transform .16s ease}.orb-state-enter-from{opacity:0;transform:translateY(5px) scale(.92)}.orb-state-leave-to{opacity:0;transform:translateY(-5px) scale(.92)}
@keyframes detail-in{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:none}}@keyframes orb-spin{to{transform:rotate(360deg)}}@keyframes saved-check{from{stroke-dashoffset:36;transform:scale(.65)}to{stroke-dashoffset:0;transform:scale(1)}}@keyframes saved-ring{from{transform:scale(.85);opacity:.1}to{transform:scale(1);opacity:1}}@keyframes candidate-in{from{opacity:0;transform:translateY(4px)}to{opacity:1;transform:translateY(0)}}
@media(hover:none){.orb-action{opacity:1;visibility:visible;transform:none}}@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
.orb-start-title{display:flex;align-items:center;gap:7px;position:absolute;top:4px;left:8px;width:calc(var(--orb-size) - 16px);height:30px;box-sizing:border-box;padding:6px 12px;border:1px solid var(--yp-border-subtle);border-radius:10px;background:var(--yp-bg-surface);color:var(--yp-text-primary);box-shadow:0 2px 6px #192b4612;font-size:12px;line-height:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;pointer-events:none;transform-origin:50% 100%}.details-left .orb-start-title{left:calc(var(--detail-width) + 8px)}
.orb-title-text{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.orb-title-dot{width:5px;height:5px;border-radius:50%;background:var(--yp-action-primary);flex-shrink:0}
.orb-title-enter-active{transition:opacity .22s ease,transform .28s cubic-bezier(.2,.8,.2,1)}.orb-title-leave-active{transition:opacity .16s ease,transform .18s ease}.orb-title-enter-from,.orb-title-leave-to{opacity:0;transform:translateY(6px) scale(.96)}
.is-desktop .timer-orb{position:absolute;left:var(--orb-left);top:var(--orb-head)}.is-desktop .orb-details{left:calc(var(--orb-left) + var(--orb-size));right:auto;width:var(--detail-width)}.is-desktop .details-left .orb-details{left:calc(var(--orb-left) - var(--detail-width));right:auto}.is-desktop .orb-start-title{left:calc(var(--orb-left) + 8px)}
</style>
