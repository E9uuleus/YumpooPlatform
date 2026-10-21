<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ProjectLifecycle } from '@yumpoo/api-client'
import type { RailProject, RailState } from '@yumpoo/preload-contract'
import { Plus } from '@element-plus/icons-vue'
import { useSession } from '../../composables/useSession'
import { useTimeTracker } from '../../composables/useTimeTracker'
import { useProjectRecents } from '../../composables/useProjectRecents'
import { useDesktopSurfaceOpacity } from '../../composables/useDesktopSurfaceOpacity'
import { projectColors } from '../../components/team/teamDashboardModel'
import { orbDuration } from '../../components/timer/timeFormat'
import TimerIcon from '../../components/timer/TimerIcon.vue'
import { projectInitial } from '../../components/desktop/quickTime'
import { plainRailProjects } from '../../components/desktop/railProjects'

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
const tones = computed(() => projectColors(projects.value.map(project => project.id)))
const icon = computed(() => ICONS[state.value?.size ?? 'medium'])
const running = computed(() => tracker.running.value.length > 0)
const resume = computed(() => tracker.current.value?.resumeItems ?? [])
const slotLabel = computed(() => running.value ? orbDuration(tracker.wallDuration.value) : resume.value.length ? '继续' : '计时')

const offState = bridge?.onState(value => { state.value = value })
void bridge?.getState().then(value => { state.value ??= value }).catch(() => undefined)
onBeforeUnmount(() => offState?.())

// Until the person arranges it, the rail starts from their most recent projects; another account's list is replaced the same way.
// With no recents yet (a fresh install or partition) nothing is saved, so the rail seeds once the main window records some.
watch([state, scope, () => recents.items.value], ([value, owner]) => {
  if (!bridge || !value || !owner || value.projects?.owner === owner) return
  const items = recents.items.value.filter(project => project.lifecycle === ProjectLifecycle.Active).slice(0, 6)
    .map(project => ({ id: project.id, name: project.name.slice(0, 64) }))
  if (items.length) void bridge.setProjects({ owner, items }).catch(() => undefined)
})

async function toggleTimer() {
  if (tracker.busy.value) return
  if (running.value) await tracker.stopAll()
  else if (resume.value.length) await tracker.startMany(resume.value.map(item => item.workItemId))
}
function open(project: RailProject, event: MouseEvent) {
  void bridge?.openProject(project.id, (event.currentTarget as HTMLElement).getBoundingClientRect().top)
}
function drop(target: number) {
  const from = dragging.value
  dragging.value = null
  if (from === null || from === target || !scope.value) return
  const items = [...projects.value]
  const [moved] = items.splice(from, 1)
  items.splice(target, 0, moved!)
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
        v-for="(project, index) in projects"
        :key="project.id"
        type="button"
        class="rail-project"
        :class="{ 'is-active': state.active === project.id, 'is-dragging': dragging === index }"
        :style="{ background: tones.get(project.id) }"
        :title="project.name"
        :aria-label="`${project.name}：记录工时`"
        draggable="true"
        @click="open(project, $event)"
        @dragstart="dragging = index"
        @dragover.prevent
        @drop.prevent="drop(index)"
        @dragend="dragging = null"
      >
        {{ projectInitial(project.name) }}
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
  </div>
</template>

<style scoped>
.project-rail{box-sizing:border-box;display:flex;height:100vh;padding:10px 6px 10px 10px;font-family:var(--yp-font-family);user-select:none}
.project-rail.is-left{padding:10px 10px 10px 6px}
.rail{box-sizing:border-box;display:flex;flex-direction:column;align-items:center;gap:8px;width:calc(var(--rail-icon) + 14px);height:100%;padding:10px 0;border-radius:var(--yp-timer-radius);
  background:color-mix(in srgb,var(--yp-bg-raised) var(--yp-desktop-surface-alpha,96%),transparent);
  box-shadow:inset 0 0 0 1px var(--yp-timer-hairline),var(--yp-timer-shadow),var(--yp-timer-highlight);
  transition:transform .3s var(--yp-timer-ease);-webkit-app-region:drag;app-region:drag}
/* Tucked, 4px of the rail stay in view beside the 6px gap: the 10px strip the shell keeps hit-testable. */
.is-right .rail.is-tucked{transform:translateX(calc(var(--rail-icon) + 10px))}
.is-left .rail.is-tucked{transform:translateX(calc(-1 * (var(--rail-icon) + 10px)))}
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
.rail-project{display:grid;place-items:center;width:var(--rail-icon);height:var(--rail-icon);border-radius:10px;color:var(--el-color-white);font-size:calc(var(--rail-icon) * .38);font-weight:600;flex-shrink:0;transition:transform .15s,opacity .15s}
.rail-project:hover{transform:scale(1.08)}
.rail-project.is-active{outline:2px solid var(--yp-timer-accent);outline-offset:2px}
.rail-project.is-dragging{opacity:.35}
.rail-project.is-add{box-sizing:border-box;border:1px dashed color-mix(in srgb,var(--yp-text-primary) 32%,transparent);background:none;color:var(--yp-text-secondary)}
.rail-project.is-add svg{width:16px;height:16px}
@media(prefers-reduced-motion:reduce){*{transition:none!important}}
</style>
