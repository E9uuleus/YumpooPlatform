<script setup lang="ts">
import { computed } from 'vue'
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

const lines = computed(() => dockDuration(props.duration))
const toggleLabel = computed(() => props.running ? '暂停计时' : props.canResume ? '继续计时' : '查找工作项')
const status = computed(() => props.offline ? '等待连接' : props.saved ? '已保存' : props.running ? '计时中' : props.canResume ? '已暂停' : '未开始')
</script>

<template>
  <div
    class="timer-dock"
    :class="[`dock-${side}`, { 'is-running': running, 'is-saved': saved, 'is-offline': offline, 'is-busy': busy }]"
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
      <span class="status-dot" />
      <span
        v-if="saved"
        class="tab-glyph saved"
      ><TimerIcon name="check" /></span>
      <span
        v-else-if="!running && !canResume"
        class="tab-glyph"
      ><TimerIcon name="clock" /></span>
      <span
        v-else
        class="tab-time"
      ><span>{{ lines[0] }}</span><span>{{ lines[1] }}</span></span>
      <span
        class="tab-grip"
        aria-hidden="true"
      />
    </div>
  </div>
</template>

<style scoped>
.timer-dock{position:absolute;inset:0;--dot:var(--yp-text-disabled)}
.is-running{--dot:var(--yp-timer-accent)}.is-saved{--dot:var(--yp-timer-saved)}.is-offline{--dot:var(--yp-timer-offline)}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0;-webkit-app-region:no-drag;app-region:no-drag}
button:disabled{cursor:default;opacity:.55}
button:focus-visible,.dock-tab:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:2px}
.status-dot{width:6px;height:6px;border-radius:50%;background:var(--dot);flex-shrink:0}
.is-running .status-dot{box-shadow:0 0 0 3px color-mix(in srgb,var(--dot) 18%,transparent);animation:pulse 2.4s ease-in-out infinite}
.dock-tab{position:absolute;top:50%;width:32px;height:96px;margin-top:-48px;box-sizing:border-box;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:7px;
  background:var(--yp-timer-surface);border:1px solid var(--yp-timer-hairline);box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight);color:var(--yp-text-primary);
  cursor:grab;user-select:none;-webkit-app-region:drag;app-region:drag}
.dock-right .dock-tab{right:0;border-right:0;border-radius:12px 0 0 12px}
.dock-left .dock-tab{left:0;border-left:0;border-radius:0 12px 12px 0}
.tab-time{display:flex;flex-direction:column;align-items:center;gap:1px;font-size:11px;line-height:13px;font-weight:650;font-variant-numeric:tabular-nums;letter-spacing:-.02em}
.tab-time span:last-child{color:var(--yp-text-muted);font-weight:550}
.tab-glyph{display:grid;place-items:center;color:var(--yp-timer-accent)}.tab-glyph svg{width:16px;height:16px}.tab-glyph.saved{color:var(--yp-timer-saved)}
.tab-grip{width:3px;height:10px;border-radius:2px;background:radial-gradient(circle,var(--yp-text-disabled) 1px,transparent 1.2px) 0 0/3px 4px repeat-y;opacity:.8}
.dock-card{position:absolute;width:288px;height:152px;box-sizing:border-box;display:flex;flex-direction:column;padding:14px 14px 10px 16px;
  background:var(--yp-timer-surface);border:1px solid var(--yp-timer-hairline);box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight);color:var(--yp-text-primary);
  -webkit-app-region:drag;app-region:drag;user-select:none}
.dock-right .dock-card{border-right:0;border-radius:16px 0 0 16px}
.dock-left .dock-card{border-left:0;border-radius:0 16px 16px 0;padding:14px 16px 10px 14px}
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
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
</style>
