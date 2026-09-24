<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { formatDuration } from '../../composables/useTimeTracker'
import TimerIcon from './TimerIcon.vue'
import TimerDigits from './TimerDigits.vue'
import { orbDuration } from './timeFormat'

const props = defineProps<{
  size: number
  side: 'left' | 'right' | null
  detailWidth: number
  left?: number | undefined
  top?: number | undefined
  open: boolean
  active: boolean
  dragging: boolean
  running: boolean
  saved: boolean
  busy: boolean
  offline: boolean
  duration: number
  title?: string | undefined
  project?: string | undefined
  hasWork: boolean
  canResume: boolean
  toggleDisabled: boolean
}>()
const emit = defineEmits<{ toggle: []; switch: []; open: []; drag: [event: PointerEvent]; press: [event: PointerEvent]; closed: []; reveal: [] }>()

const focusWithin = ref(false)
const idle = computed(() => !props.running && !props.canResume)
/** The play/pause control takes the place of the time while the orb is hovered or focused, and always when there is nothing to show. */
const controlShown = computed(() => !props.dragging && (props.active || focusWithin.value || idle.value))
const progress = computed(() => props.running ? (props.duration % 60000) / 600 : 0)
const wrapped = ref(false)
watch(progress, (value, previous) => { wrapped.value = value < previous })
const toggleLabel = computed(() => props.running ? '暂停计时' : props.canResume ? '继续计时' : '查找工作项')
const icon = computed(() => props.busy ? 'loader' : props.running ? 'pause' : 'play')
const context = computed(() => props.project || (props.running ? '当前工作' : props.canResume ? '上次工作' : '选择工作开始计时'))
const status = computed(() => props.running ? '计时中' : props.canResume ? '已暂停' : '未开始计时')
</script>

<template>
  <div
    class="orb-shell"
    :class="[`side-${side ?? 'none'}`, { 'is-open': open, 'is-running': running, 'is-saved': saved, 'is-busy': busy, 'is-paused': !running && canResume, 'is-control': controlShown, 'is-dragging': dragging }]"
    :style="{ '--orb-size': `${size}px`, '--capsule-width': `${size + detailWidth}px`, left: left === undefined ? undefined : `${left}px`, top: top === undefined ? undefined : `${top}px` }"
    @focusin="focusWithin = true"
    @focusout="focusWithin = false"
  >
    <Transition
      name="orb-capsule"
      @after-leave="emit('closed')"
    >
      <div
        v-if="open && side"
        class="orb-capsule"
      >
        <div class="capsule-body">
          <div class="capsule-copy">
            <button
              class="capsule-title"
              :disabled="!hasWork"
              @click="emit('open')"
            >
              {{ title || '尚未选择工作项' }}
            </button>
            <span class="capsule-meta">
              <span class="capsule-project">{{ context }}</span>
              <span
                v-if="!idle"
                class="capsule-time"
              >{{ formatDuration(duration) }}</span>
            </span>
          </div>
          <button
            class="capsule-switch"
            aria-label="更换工作"
            @click="emit('switch')"
          >
            <TimerIcon name="swap" />
          </button>
        </div>
      </div>
    </Transition>
    <div
      class="orb-disc"
      role="group"
      :aria-label="status"
      @pointerdown="emit('drag', $event)"
    >
      <svg
        class="orb-ring"
        viewBox="0 0 100 100"
        aria-hidden="true"
      >
        <circle
          class="ring-track"
          cx="50"
          cy="50"
          r="46"
        />
        <circle
          class="ring-arc"
          :class="{ 'no-sweep': wrapped }"
          cx="50"
          cy="50"
          r="46"
          pathLength="100"
          :style="{ strokeDashoffset: 100 - progress }"
        />
      </svg>
      <div
        class="orb-face"
        :aria-hidden="controlShown"
      >
        <Transition
          name="orb-state"
          mode="out-in"
        >
          <span
            v-if="saved"
            key="saved"
            class="orb-saved saved-feedback"
            role="status"
          ><TimerIcon name="check" /><span class="sr-only">已保存</span></span>
          <TimerDigits
            v-else-if="!idle"
            key="digits"
            :text="orbDuration(duration)"
          />
        </Transition>
      </div>
      <button
        class="orb-control"
        :class="{ 'is-pause': running }"
        :aria-label="toggleLabel"
        :disabled="toggleDisabled"
        @pointerdown.stop="emit('press', $event)"
        @click="emit('toggle')"
        @focus="emit('reveal')"
      >
        <Transition
          name="orb-icon"
          mode="out-in"
        >
          <TimerIcon
            :key="icon"
            :name="icon"
            :class="{ spinning: busy }"
          />
        </Transition>
      </button>
      <span
        v-if="offline"
        class="orb-offline"
        role="status"
        aria-label="等待连接"
      />
    </div>
  </div>
</template>

<style scoped>
.orb-shell{position:absolute;left:0;top:0;width:var(--orb-size);height:var(--orb-size);--ring:var(--yp-timer-accent);--spring:cubic-bezier(.34,1.56,.64,1)}
.orb-shell.is-saved{--ring:var(--yp-timer-saved)}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0;-webkit-app-region:no-drag;app-region:no-drag}
button:disabled{cursor:default;opacity:.5}
button:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:2px}
.orb-disc{position:absolute;inset:0;z-index:1;display:grid;place-items:center;box-sizing:border-box;border-radius:50%;border:1px solid var(--yp-timer-hairline);
  background:radial-gradient(circle at 32% 22%,color-mix(in srgb,#fff 12%,transparent),transparent 58%),var(--yp-timer-surface);
  box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight);color:var(--yp-text-primary);cursor:grab;user-select:none;touch-action:none;-webkit-app-region:drag;app-region:drag;
  transition:box-shadow .25s ease,transform .25s var(--yp-timer-ease)}
.is-open .orb-disc{box-shadow:var(--yp-timer-shadow-soft),var(--yp-timer-highlight)}
.is-dragging .orb-disc{cursor:grabbing;transform:scale(1.05);box-shadow:0 2px 4px color-mix(in srgb,#0b1220 14%,transparent),0 10px 18px -6px color-mix(in srgb,#0b1220 32%,transparent),var(--yp-timer-highlight)}
.orb-ring{position:absolute;inset:3px;width:calc(100% - 6px);height:calc(100% - 6px);transform:rotate(-90deg);pointer-events:none}
.ring-track{fill:none;stroke:var(--yp-timer-hairline);stroke-width:3;transition:stroke .3s ease}
.is-control .ring-track{stroke:color-mix(in srgb,var(--ring) 28%,var(--yp-timer-hairline))}
.ring-arc{fill:none;stroke:var(--ring);stroke-width:4;stroke-linecap:round;stroke-dasharray:100;opacity:0;transition:stroke-dashoffset 1s linear,opacity .3s ease}
.ring-arc.no-sweep{transition:opacity .3s ease}
.is-running .ring-arc{opacity:1;filter:drop-shadow(0 0 2px color-mix(in srgb,var(--ring) 45%,transparent))}
.is-saved .ring-track{stroke:var(--ring);animation:saved-ring .6s var(--yp-timer-ease) both}
.is-busy .orb-ring{animation:spin 1.1s linear infinite}
.is-busy .ring-arc{opacity:1;stroke-dashoffset:72!important;transition:none}
.orb-face{position:absolute;inset:0;display:grid;place-items:center;pointer-events:none;transition:opacity .2s ease,transform .34s var(--yp-timer-ease),filter .22s ease}
.is-control .orb-face{opacity:0;transform:scale(.6);filter:blur(3px)}
.orb-face :deep(.timer-digits){font-size:calc(var(--orb-size) * .23);font-weight:650;letter-spacing:-.02em}
.is-paused .orb-face :deep(.timer-digits){color:var(--yp-text-secondary)}
.orb-saved{display:grid;place-items:center;color:var(--yp-timer-saved)}
.orb-saved svg{width:calc(var(--orb-size) * .32);height:calc(var(--orb-size) * .32);stroke-dasharray:36;animation:saved-check .42s ease both}
.orb-control{position:relative;z-index:1;display:grid;place-items:center;width:calc(var(--orb-size) * .52);height:calc(var(--orb-size) * .52);border-radius:50%;
  background:var(--yp-timer-accent);color:#fff;box-shadow:0 4px 12px -3px color-mix(in srgb,var(--yp-timer-accent) 60%,transparent);
  opacity:0;visibility:hidden;transform:scale(.4) rotate(-90deg);-webkit-app-region:drag;app-region:drag;
  transition:opacity .18s ease,transform .42s var(--spring),background .2s ease,color .2s ease,box-shadow .2s ease,visibility 0s linear .42s}
.is-control .orb-control{opacity:1;visibility:visible;transform:none;-webkit-app-region:no-drag;app-region:no-drag;transition:opacity .2s ease .05s,transform .46s var(--spring) .03s,background .2s ease,color .2s ease,box-shadow .2s ease,visibility 0s}
.orb-control.is-pause{background:color-mix(in srgb,var(--yp-timer-accent) 14%,var(--yp-bg-raised));color:var(--yp-timer-accent);box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--yp-timer-accent) 30%,transparent)}
.is-control .orb-control:not(:disabled):hover{transform:scale(1.07)}
.is-control .orb-control:not(:disabled):active{transform:scale(.9);transition-duration:.12s}
.orb-control svg{width:42%;height:42%}
.orb-control:not(.is-pause) svg{margin-left:6%}
.orb-offline{position:absolute;top:calc(var(--orb-size) * .1);right:calc(var(--orb-size) * .1);z-index:2;width:8px;height:8px;border-radius:50%;background:var(--yp-timer-offline);box-shadow:0 0 0 2px var(--yp-bg-raised)}
.orb-capsule{position:absolute;top:0;height:var(--orb-size);width:var(--capsule-width);box-sizing:border-box;border-radius:calc(var(--orb-size) / 2);border:1px solid var(--yp-timer-hairline);
  background:var(--yp-timer-surface);box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight);overflow:hidden;-webkit-app-region:no-drag;app-region:no-drag;cursor:default;
  transition:width .32s var(--yp-timer-ease),opacity .2s ease}
.side-right .orb-capsule{left:0}
.side-left .orb-capsule{right:0}
.capsule-body{display:flex;align-items:center;gap:8px;height:100%;box-sizing:border-box;padding:0 calc(var(--orb-size) * .16) 0 calc(var(--orb-size) + 10px);transition:opacity .22s ease .08s,transform .3s var(--yp-timer-ease) .04s}
.side-left .capsule-body{flex-direction:row-reverse;padding:0 calc(var(--orb-size) + 10px) 0 calc(var(--orb-size) * .16)}
.capsule-copy{display:flex;flex-direction:column;gap:2px;min-width:0;flex:1}
.side-left .capsule-copy{align-items:flex-end;text-align:right}
.capsule-title{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;font-weight:600;line-height:18px;text-align:inherit;color:var(--yp-text-primary)}
.capsule-title:not(:disabled):hover{color:var(--yp-timer-accent)}
.capsule-title:disabled{opacity:1;color:var(--yp-text-secondary)}
.capsule-meta{display:flex;align-items:baseline;gap:6px;max-width:100%;min-width:0;font-size:11px;line-height:15px;color:var(--yp-text-muted)}
.capsule-project{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.capsule-time{flex-shrink:0;font-variant-numeric:tabular-nums;font-weight:600;color:var(--yp-text-secondary)}
.capsule-time::before{content:'·';margin-right:6px;color:var(--yp-text-disabled);font-weight:400}
.is-running .capsule-time{color:var(--yp-timer-accent)}
.capsule-switch{display:grid;place-items:center;width:30px;height:30px;flex-shrink:0;border-radius:50%;color:var(--yp-text-secondary);transition:transform .16s ease,background .16s ease}
.capsule-switch:hover{background:var(--yp-timer-hover);color:var(--yp-text-primary)}
.capsule-switch svg{width:15px;height:15px}
.capsule-switch:active{transform:scale(.9)}
.orb-capsule-enter-from,.orb-capsule-leave-to{width:var(--orb-size);opacity:.4}
.orb-capsule-enter-from .capsule-body,.orb-capsule-leave-to .capsule-body{opacity:0;transform:translateX(-8px)}
.side-left .orb-capsule-enter-from .capsule-body,.side-left .orb-capsule-leave-to .capsule-body{transform:translateX(8px)}
.orb-capsule-leave-active{transition:width .22s ease,opacity .18s ease .04s}
.orb-capsule-leave-active .capsule-body{transition:opacity .1s ease,transform .16s ease}
.orb-state-enter-active,.orb-state-leave-active{transition:opacity .16s ease,transform .16s ease}
.orb-state-enter-from{opacity:0;transform:translateY(4px) scale(.92)}
.orb-state-leave-to{opacity:0;transform:translateY(-4px) scale(.92)}
.orb-icon-enter-active,.orb-icon-leave-active{transition:opacity .14s ease,transform .22s var(--yp-timer-ease)}
.orb-icon-enter-from{opacity:0;transform:scale(.4) rotate(-90deg)}
.orb-icon-leave-to{opacity:0;transform:scale(.4) rotate(90deg)}
.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}
.spinning{animation:spin 1s linear infinite}
@keyframes spin{to{transform:rotate(360deg)}}
@keyframes saved-ring{from{opacity:.2}to{opacity:1}}
@keyframes saved-check{from{stroke-dashoffset:36;transform:scale(.65)}to{stroke-dashoffset:0;transform:scale(1)}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
</style>
