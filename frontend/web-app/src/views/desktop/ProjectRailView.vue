<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ProjectLifecycle, ProjectLifecycleFilter } from '@yumpoo/api-client'
import type { RailProject, RailState } from '@yumpoo/preload-contract'
import { Plus } from '@element-plus/icons-vue'
import { projectsApi } from '../../api/client'
import { useSession } from '../../composables/useSession'
import { useTimeTracker } from '../../composables/useTimeTracker'
import { useProjectRecents } from '../../composables/useProjectRecents'
import { useDesktopSurfaceOpacity } from '../../composables/useDesktopSurfaceOpacity'
import { projectColors } from '../../components/team/teamDashboardModel'
import { orbDuration } from '../../components/timer/timeFormat'
import TimerIcon from '../../components/timer/TimerIcon.vue'
import { railLabel } from '../../components/desktop/quickTime'
import { plainRailProjects } from '../../components/desktop/railProjects'
import { resumeSubjects } from '../../components/timer/timeCategories'
import { MY_PROJECT_ACCESSES } from '../../components/projects/projectScope'

// Mirrors RAIL_ICONS and the 10px padding, 8px gaps and 14px time label that timer-geometry.ts sizes the window by.
const ICONS = { small: 30, medium: 34, large: 40 } as const
const bridge = window.yumpooDesktop?.rail
const session = useSession()
const tracker = useTimeTracker()
useDesktopSurfaceOpacity()
const state = ref<RailState>()
const dragging = ref<number | null>(null)
const scope = computed(() => {
  const authentication = session.authentication.value
  return authentication ? `${authentication.company.id}:${authentication.user.id}` : undefined
})
const recents = useProjectRecents(() => scope.value)
const projects = computed(() => state.value?.projects && state.value.projects.owner === scope.value ? state.value.projects.items : [])
const available = ref<Set<string>>()
const visibleProjects = computed(() => available.value ? projects.value.filter(project => available.value!.has(project.id)) : projects.value)
watch(() => visibleProjects.value.length, count => { void bridge?.setVisibleCount?.(count) }, { immediate: true })
const tones = computed(() => projectColors(visibleProjects.value.map(project => project.id)))
const icon = computed(() => ICONS[state.value?.size ?? 'medium'])
const running = computed(() => tracker.running.value.length > 0)
const resume = computed(() => resumeSubjects(tracker.current.value))
const slotLabel = computed(() => running.value ? orbDuration(tracker.wallDuration.value) : resume.value.length ? '继续' : '计时')

let availabilityRequest = 0
let availabilityInFlight: Promise<void> | undefined
const offState = bridge?.onState(value => { state.value = value })
void bridge?.getState().then(value => { state.value ??= value }).catch(() => undefined)
window.addEventListener('focus', readAvailableProjects)
document.addEventListener('visibilitychange', refreshWhenVisible)
onBeforeUnmount(() => {
  availabilityRequest++
  offState?.()
  window.removeEventListener('focus', readAvailableProjects)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})

// Until the person arranges it, the rail starts from their most recent projects; another account's list is replaced the same way.
// With no recents yet (a fresh install or partition) nothing is saved, so the rail seeds once the main window records some.
watch([state, scope, () => recents.items.value], ([value, owner]) => {
  if (!bridge || !value || !owner || value.projects?.owner === owner) return
  const items = recents.items.value.filter(project => project.lifecycle === ProjectLifecycle.Active && !project.endedAt).slice(0, 6)
    .map(project => ({ id: project.id, name: project.name.slice(0, 64) }))
  if (items.length) void bridge.setProjects({ owner, items }).catch(() => undefined)
})

watch(scope, () => {
  availabilityRequest++
  availabilityInFlight = undefined
  available.value = undefined
  void readAvailableProjects()
}, { immediate: true })
function readAvailableProjects(): Promise<void> {
  if (availabilityInFlight) return availabilityInFlight
  if (!scope.value) return Promise.resolve()
  const request = ++availabilityRequest
  availabilityInFlight = projectsApi.listProjects({ lifecycle: ProjectLifecycleFilter.Active, ended: false, actorAccesses: MY_PROJECT_ACCESSES, page: 0, size: 100 })
    .then(result => { if (request === availabilityRequest) available.value = new Set(result.items.map(project => project.id)) })
    .catch(() => undefined)
    .finally(() => { if (request === availabilityRequest) availabilityInFlight = undefined })
  return availabilityInFlight
}
function refreshWhenVisible() {
  if (document.visibilityState === 'visible') void readAvailableProjects()
}

async function toggleTimer() {
  if (tracker.busy.value) return
  if (running.value) await tracker.stopAll()
  else if (resume.value.length) await tracker.startMany(resume.value)
}
function open(project: RailProject, event: MouseEvent) {
  void bridge?.openProject(project.id, (event.currentTarget as HTMLElement).getBoundingClientRect().top)
}
function drop(target: number) {
  const from = dragging.value
  dragging.value = null
  if (from === null || from === target || !scope.value) return
  const ordered = [...visibleProjects.value]
  const [moved] = ordered.splice(from, 1)
  ordered.splice(target, 0, moved!)
  const visibleIds = new Set(visibleProjects.value.map(project => project.id))
  let next = 0
  const items = projects.value.map(project => visibleIds.has(project.id) ? ordered[next++]! : project)
  void bridge?.setProjects({ owner: scope.value, items: plainRailProjects(items) })
}
</script>

<template>
  <div
    v-if="state"
    class="project-rail"
    :class="`is-${state.side}`"
    :style="{ '--rail-icon': `${icon}px` }"
  >
    <nav
      class="rail"
      :class="{ 'is-tucked': state.tucked }"
      aria-label="项目侧边栏"
    >
      <template v-if="state.timer">
        <div class="rail-timer">
          <button
            type="button"
            class="rail-ring"
            :class="{ 'is-running': running }"
            :disabled="!running && !resume.length"
            :aria-label="running ? '暂停计时' : resume.length ? '继续计时' : '在小窗口中选择工作项后开始计时'"
            :title="running ? '暂停计时' : resume.length ? '继续计时' : '点击项目，在小窗口中开始计时'"
            @click="toggleTimer"
          >
            <TimerIcon :name="running ? 'pause' : resume.length ? 'play' : 'clock'" />
          </button>
          <span
            class="rail-time"
            :class="{ 'is-running': running }"
          >{{ slotLabel }}</span>
        </div>
        <span class="rail-sep" />
      </template>
      <button
        v-for="(project, index) in visibleProjects"
        :key="project.id"
        type="button"
        class="rail-project"
        :class="{ 'is-active': state.active === project.id, 'is-dragging': dragging === index }"
        :style="{ '--tone': tones.get(project.id) }"
        :title="project.name"
        :data-len="[...railLabel(project)].length"
        :aria-label="`${project.name}：记录工时`"
        draggable="true"
        @click="open(project, $event)"
        @dragstart="dragging = index"
        @dragover.prevent
        @drop.prevent="drop(index)"
        @dragend="dragging = null"
      >
        {{ railLabel(project) }}
      </button>
      <button
        type="button"
        class="rail-project is-add"
        title="在偏好设置中管理项目"
        aria-label="管理侧边栏项目"
        @click="bridge?.openSettings()"
      >
        <Plus />
      </button>
    </nav>
    <span
      v-if="state.mode === 'autohide'"
      class="rail-handle"
      :class="{ 'is-shown': state.tucked, 'is-running': running }"
      aria-hidden="true"
    />
  </div>
</template>

<style scoped>
.project-rail{position:relative;box-sizing:border-box;display:flex;height:100vh;padding:10px 6px 10px 10px;font-family:var(--yp-font-family);user-select:none}
.project-rail.is-left{padding:10px 10px 10px 6px}
.rail{box-sizing:border-box;display:flex;flex-direction:column;align-items:center;gap:8px;width:calc(var(--rail-icon) + 14px);height:100%;padding:10px 0;border-radius:var(--yp-timer-radius);
  background:color-mix(in srgb,var(--yp-bg-raised) var(--yp-desktop-surface-alpha,96%),transparent);
  box-shadow:inset 0 0 0 1px var(--yp-timer-hairline),var(--yp-timer-shadow),var(--yp-timer-highlight);
  transition:transform .3s var(--yp-timer-ease),opacity .2s;-webkit-app-region:drag;app-region:drag}
/* Tucked, the rail and its shadow leave the screen entirely; the 10px strip the shell keeps hit-testable shows only the handle. */
.rail.is-tucked{opacity:0;transition:transform .3s var(--yp-timer-ease),opacity .2s .1s}
.is-right .rail.is-tucked{transform:translateX(calc(100% + 7px))}
.is-left .rail.is-tucked{transform:translateX(calc(-100% - 7px))}
.rail-handle{position:absolute;top:50%;width:4px;height:40px;border-radius:2px;background:color-mix(in srgb,var(--yp-text-primary) 28%,transparent);
  box-shadow:0 0 0 1px color-mix(in srgb,var(--yp-bg-raised) 70%,transparent);opacity:0;transform:translateY(-50%);transition:opacity .12s;pointer-events:none}
.is-left .rail-handle{left:3px}
.is-right .rail-handle{right:3px}
.rail-handle.is-shown{opacity:1;transition:opacity .2s .25s}
.rail-handle.is-running{background:var(--yp-timer-accent);box-shadow:0 0 8px color-mix(in srgb,var(--yp-timer-accent) 55%,transparent)}
.rail-handle.is-shown.is-running{animation:rail-breathe 2.4s ease-in-out .45s infinite}
@keyframes rail-breathe{0%,100%{opacity:1}50%{opacity:.5}}
button{font:inherit;cursor:pointer;border:0;padding:0;-webkit-app-region:no-drag;app-region:no-drag}
button:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:2px}
.rail-timer{display:flex;flex-direction:column;align-items:center;height:calc(var(--rail-icon) + 14px);flex-shrink:0}
.rail-ring{display:grid;place-items:center;width:var(--rail-icon);height:var(--rail-icon);border-radius:50%;background:none;color:var(--yp-text-muted);box-shadow:inset 0 0 0 2px var(--yp-timer-hairline)}
.rail-ring.is-running{color:var(--yp-timer-accent);box-shadow:inset 0 0 0 2px var(--yp-timer-accent)}
.rail-ring:disabled{cursor:default}
.rail-ring svg{width:15px;height:15px}
.rail-time{height:14px;color:var(--yp-text-muted);font-size:11px;line-height:14px;font-variant-numeric:tabular-nums}
.rail-time.is-running{color:var(--yp-timer-accent)}
.rail-sep{width:24px;height:1px;background:var(--yp-timer-hairline);flex-shrink:0}
/* Tinted tiles: the project colour washes the tile and darkens (or, in dark themes, lightens) into the letter for contrast. */
.rail-project{position:relative;display:grid;place-items:center;width:var(--rail-icon);height:var(--rail-icon);border-radius:calc(var(--rail-icon) * .3);
  background:color-mix(in srgb,var(--tone) 17%,var(--yp-bg-raised));color:color-mix(in srgb,var(--tone) 62%,var(--yp-text-primary));
  box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--tone) 30%,transparent);font-size:calc(var(--rail-icon) * .4);font-weight:700;flex-shrink:0;
  transition:background .15s,opacity .15s}
.rail-project{white-space:nowrap}
.rail-project[data-len="2"]{font-size:calc(var(--rail-icon) * .36);letter-spacing:-.02em}
.rail-project[data-len="3"]{font-size:clamp(10px,calc(var(--rail-icon) * .3),13px);letter-spacing:-.04em}
.rail-project:hover{background:color-mix(in srgb,var(--tone) 28%,var(--yp-bg-raised))}
/* The open project carries a short bar on the rail's screen-edge side instead of an outline. */
.rail-project.is-active::before{content:"";position:absolute;top:50%;width:3px;height:16px;border-radius:2px;background:var(--yp-timer-accent);transform:translateY(-50%)}
.is-left .rail-project.is-active::before{left:-7px}
.is-right .rail-project.is-active::before{right:-7px}
.rail-project.is-dragging{opacity:.35}
.rail-project.is-add{background:none;box-shadow:none;color:var(--yp-text-muted)}
.rail-project.is-add:hover{background:var(--yp-timer-hover);color:var(--yp-text-secondary)}
.rail-project.is-add svg{width:16px;height:16px}
@media(prefers-reduced-motion:reduce){*{transition:none!important;animation:none!important}}
</style>
