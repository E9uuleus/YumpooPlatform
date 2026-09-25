<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { TimerCandidate } from '@yumpoo/api-client'
import TimerIcon from './TimerIcon.vue'
import TimerDigits from './TimerDigits.vue'
import WorkItemGlyph from './WorkItemGlyph.vue'
import { dockDuration } from './timeFormat'

const props = defineProps<{
  side: 'left' | 'right'
  left: number
  top: number
  expanded: boolean
  active: boolean
  dragging: boolean
  running: boolean
  saved: boolean
  busy: boolean
  offline: boolean
  duration: number
  title?: string | undefined
  project?: string | undefined
  item?: TimerCandidate | undefined
  hasWork: boolean
  canResume: boolean
  toggleDisabled: boolean
}>()
const emit = defineEmits<{ toggle: []; switch: []; open: []; closed: []; expand: [] }>()

const reading = computed(() => dockDuration(props.duration))
const progress = computed(() => props.running ? (props.duration % 60000) / 600 : 0)
const wrapped = ref(false)
watch(progress, (value, previous) => { wrapped.value = value < previous })
const idle = computed(() => !props.running && !props.canResume)
const toggleLabel = computed(() => props.running ? '暂停计时' : props.canResume ? '继续计时' : '查找工作项')
const status = computed(() => props.offline ? '等待连接' : props.saved ? '已保存' : props.running ? '计时中' : props.canResume ? '已暂停' : '未开始')
</script>

<template>
  <div
    class="timer-dock"
    :class="[`dock-${side}`, { 'is-running': running, 'is-saved': saved, 'is-offline': offline, 'is-busy': busy, 'is-active': active, 'is-dragging': dragging }]"
  >
    <Transition
      name="dock-card"
      @after-leave="emit('closed')"
    >
      <section
        v-if="expanded"
        class="dock-card"
        :style="{ left: `${left}px`, top: `${top}px` }"
        aria-label="计时侧边栏"
      >
        <header class="dock-head">
          <WorkItemGlyph
            v-if="item"
            :code="item.contentCode"
            :name="item.contentName"
            :color-token="item.contentColorToken"
          />
          <span
            v-else
            class="dock-glyph"
          ><TimerIcon name="clock" /></span>
          <div class="dock-identity">
            <button
              class="dock-title"
              :disabled="!hasWork"
              @click="emit('open')"
            >
              {{ title || '尚未选择工作' }}
            </button>
            <span class="dock-project">{{ project || (hasWork ? '当前工作' : '选择一项工作开始计时') }}</span>
          </div>
        </header>
        <div class="dock-timer">
          <TimerDigits :duration="duration" />
          <button
            class="dock-toggle"
            :aria-label="toggleLabel"
            :disabled="toggleDisabled"
            @click="emit('toggle')"
          >
            <TimerIcon
              :name="busy ? 'loader' : running ? 'pause' : canResume ? 'play' : 'search'"
              :class="{ spinning: busy }"
            />
            <span>{{ running ? '暂停' : canResume ? '继续' : '选择工作' }}</span>
          </button>
        </div>
        <footer class="dock-foot">
          <span
            class="dock-status"
            role="status"
          ><span class="status-dot" />{{ status }}</span>
          <button
            class="dock-switch"
            @click="emit('switch')"
          >
            <TimerIcon name="swap" />更换工作
          </button>
        </footer>
      </section>
    </Transition>
    <div
      v-show="!expanded"
      class="dock-tab"
      role="button"
      tabindex="0"
      :aria-label="`${status}，展开计时侧边栏`"
      :aria-expanded="expanded"
      @keydown.enter.prevent="emit('expand')"
      @keydown.space.prevent="emit('expand')"
    >
      <span
        class="tab-ring"
        aria-hidden="true"
      >
        <svg
          class="tab-dial"
          viewBox="0 0 24 24"
        >
          <circle
            class="tab-track"
            cx="12"
            cy="12"
            r="10"
          />
          <circle
            class="tab-arc"
            :class="{ 'no-sweep': wrapped }"
            cx="12"
            cy="12"
            r="10"
            pathLength="100"
            :style="{ strokeDashoffset: 100 - progress }"
          />
        </svg>
        <span
          v-if="running && !busy"
          class="tab-pulse"
        />
        <TimerIcon
          v-else
          class="tab-glyph"
          :name="busy ? 'loader' : saved ? 'check' : idle ? 'play' : 'pause'"
          :class="{ spinning: busy }"
        />
      </span>
      <span
        v-if="idle"
        class="tab-hint"
      >开始计时</span>
      <span
        v-else
        class="tab-reading"
      >
        <span class="tab-value is-major">{{ reading.major }}</span><span class="tab-unit">{{ reading.majorUnit }}</span>
        <span class="tab-value">{{ reading.minor }}</span><span class="tab-unit">{{ reading.minorUnit }}</span>
      </span>
    </div>
  </div>
</template>

<style scoped>
.timer-dock{position:absolute;inset:0;--dot:var(--yp-text-disabled);--tone:var(--yp-text-muted);--lift:6px}
.is-running{--dot:var(--yp-timer-accent);--tone:var(--yp-timer-accent)}.is-saved{--dot:var(--yp-timer-saved);--tone:var(--yp-timer-saved)}.is-offline{--dot:var(--yp-timer-offline);--tone:var(--yp-timer-offline)}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0;-webkit-app-region:no-drag;app-region:no-drag}
button:disabled{cursor:default;opacity:.55}
button:focus-visible,.dock-tab:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:2px}
.status-dot{width:6px;height:6px;border-radius:50%;background:var(--dot);flex-shrink:0}
.is-running .status-dot{box-shadow:0 0 0 3px color-mix(in srgb,var(--dot) 18%,transparent);animation:pulse 2.4s ease-in-out infinite}
.dock-tab{position:absolute;top:50%;width:32px;height:112px;margin-top:-56px;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;gap:8px;padding:9px 0 8px;
  background:linear-gradient(180deg,color-mix(in srgb,var(--yp-text-primary) 3%,var(--yp-timer-surface)),var(--yp-timer-surface) 45%);
  border:1px solid var(--yp-timer-hairline);box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight);color:var(--yp-text-primary);
  cursor:grab;user-select:none;-webkit-app-region:drag;app-region:drag;
  transition:transform .24s var(--yp-timer-ease),border-radius .24s var(--yp-timer-ease),box-shadow .24s ease,border-color .24s ease}
.dock-right .dock-tab{right:0;border-right-color:transparent;border-radius:16px 0 0 16px}
.dock-left .dock-tab{left:0;border-left-color:transparent;border-radius:0 16px 16px 0}
.dock-tab::after{content:'';position:absolute;top:38%;bottom:38%;width:2px;border-radius:2px;background:var(--tone);opacity:0;box-shadow:0 0 6px color-mix(in srgb,var(--tone) 45%,transparent);transition:opacity .3s ease}
.dock-right .dock-tab::after{right:0}
.dock-left .dock-tab::after{left:0}
.is-running .dock-tab::after{opacity:.7}
.is-active .dock-tab{border-color:color-mix(in srgb,var(--yp-timer-accent) 30%,var(--yp-timer-hairline));box-shadow:0 2px 4px color-mix(in srgb,#0b1220 10%,transparent),0 10px 24px -8px color-mix(in srgb,#0b1220 30%,transparent),var(--yp-timer-highlight)}
.is-active.dock-right .dock-tab{border-right-color:transparent}.is-active.dock-left .dock-tab{border-left-color:transparent}
.tab-ring{position:relative;display:grid;place-items:center;width:22px;height:22px;flex-shrink:0;color:var(--tone)}
.tab-dial{position:absolute;inset:0;width:100%;height:100%;transform:rotate(-90deg)}
.tab-track{fill:none;stroke:var(--yp-timer-hairline);stroke-width:2}
.is-saved .tab-track{stroke:var(--yp-timer-saved)}
.tab-arc{fill:none;stroke:var(--yp-timer-accent);stroke-width:2.4;stroke-linecap:round;stroke-dasharray:100;opacity:0;transition:stroke-dashoffset 1s linear,opacity .3s ease}
.tab-arc.no-sweep{transition:opacity .3s ease}
.is-running .tab-arc{opacity:1}
.tab-glyph{position:relative;width:10px;height:10px}
.tab-pulse{width:6px;height:6px;border-radius:50%;background:var(--yp-timer-accent);animation:beat 2.4s ease-in-out infinite}
.tab-reading{display:flex;flex-direction:column;align-items:center;font-variant-numeric:tabular-nums;letter-spacing:-.02em}
.tab-value{font-size:12px;font-weight:600;line-height:14px;color:var(--yp-text-secondary)}
.tab-value.is-major{font-size:16px;font-weight:700;line-height:18px;color:var(--yp-text-primary)}
.tab-unit{margin:1px 0 3px;font-size:9px;line-height:10px;color:var(--yp-text-muted)}
.tab-unit:last-child{margin-bottom:0}
.tab-hint{writing-mode:vertical-rl;font-size:11px;letter-spacing:.2em;color:var(--yp-text-secondary)}
.dock-card{position:absolute;width:288px;height:152px;box-sizing:border-box;display:flex;flex-direction:column;padding:14px 14px 10px 16px;
  background:var(--yp-timer-surface);border:1px solid var(--yp-timer-hairline);box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight);color:var(--yp-text-primary);
  -webkit-app-region:drag;app-region:drag;user-select:none;transition:transform .24s var(--yp-timer-ease),border-radius .24s var(--yp-timer-ease),border-color .24s ease}
.dock-right .dock-card{border-right-color:transparent;border-radius:16px 0 0 16px}
.dock-left .dock-card{border-left-color:transparent;border-radius:0 16px 16px 0;padding:14px 16px 10px 14px}
.is-dragging .dock-tab,.is-dragging .dock-card{border-color:var(--yp-timer-hairline);border-radius:16px;cursor:grabbing}
.is-dragging.dock-right .dock-tab,.is-dragging.dock-right .dock-card{transform:translateX(calc(var(--lift) * -1))}
.is-dragging.dock-left .dock-tab,.is-dragging.dock-left .dock-card{transform:translateX(var(--lift))}
.is-dragging .dock-tab::after{opacity:0}
.dock-head{display:flex;align-items:center;gap:10px;min-width:0}
.dock-head :deep(.work-glyph),.dock-glyph{width:30px;height:30px;flex-shrink:0}
.dock-glyph{display:grid;place-items:center;border-radius:9px;background:color-mix(in srgb,var(--yp-timer-accent) 10%,transparent);color:var(--yp-timer-accent)}.dock-glyph svg{width:16px;height:16px}
.dock-identity{display:flex;flex-direction:column;gap:2px;min-width:0;flex:1}
.dock-title{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-align:left;font-size:13px;font-weight:600;line-height:18px}
.dock-title:not(:disabled):hover{color:var(--yp-timer-accent)}.dock-title:disabled{opacity:1;color:var(--yp-text-secondary)}
.dock-project{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:11px;line-height:15px;color:var(--yp-text-muted)}
.dock-timer{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-top:auto}
.dock-timer :deep(.timer-digits){font-size:26px;font-weight:600;letter-spacing:-.03em}
.dock-toggle{display:flex;align-items:center;gap:6px;height:32px;padding:0 13px 0 11px;border-radius:999px;background:var(--yp-timer-accent);color:#fff;font-size:12px;font-weight:600;
  box-shadow:0 3px 10px -2px color-mix(in srgb,var(--yp-timer-accent) 45%,transparent);transition:transform .16s ease,box-shadow .16s ease}
.dock-toggle svg{width:14px;height:14px}
.is-running .dock-toggle{background:color-mix(in srgb,var(--yp-timer-accent) 12%,var(--yp-bg-raised));color:var(--yp-timer-accent);box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--yp-timer-accent) 22%,transparent)}
.dock-toggle:not(:disabled):active,.dock-switch:active{transform:scale(.95)}
.dock-foot{display:flex;align-items:center;justify-content:space-between;margin-top:8px;padding-top:8px;border-top:1px solid var(--yp-timer-hairline)}
.dock-status{display:flex;align-items:center;gap:6px;font-size:11px;color:var(--yp-text-muted)}
.dock-switch{display:flex;align-items:center;gap:5px;padding:4px 6px;margin-right:-6px;border-radius:7px;font-size:11.5px;color:var(--yp-text-secondary)}
.dock-switch svg{width:13px;height:13px}
.dock-switch:hover{background:var(--yp-timer-hover);color:var(--yp-text-primary)}
.dock-card-enter-active{transition:transform .3s var(--yp-timer-ease),opacity .2s ease}
.dock-card-leave-active{transition:transform .2s ease,opacity .16s ease}
.dock-right .dock-card-enter-from,.dock-right .dock-card-leave-to{transform:translateX(24px);opacity:0}
.dock-left .dock-card-enter-from,.dock-left .dock-card-leave-to{transform:translateX(-24px);opacity:0}
.spinning{animation:spin 1s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
@keyframes pulse{50%{box-shadow:0 0 0 5px color-mix(in srgb,var(--dot) 6%,transparent)}}
@keyframes beat{0%,100%{transform:scale(1);opacity:1}50%{transform:scale(.7);opacity:.55}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
</style>
