<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { TimerMenuAction, TimerMenuState } from '@yumpoo/preload-contract'
import { Bell } from '@element-plus/icons-vue'
import { formatDuration } from '../../composables/useTimeTracker'
import brandLogo from '../../assets/brand/logo.svg'
import TimerIcon from './TimerIcon.vue'

const desktop = window.yumpooDesktop?.timer
const inbox = window.yumpooDesktop?.inbox
const toasts = ref(true)
const preferencesReady = ref(false)
const savingPreferences = ref(false)
const state = ref<TimerMenuState>()
const now = ref(Date.now())
const root = ref<HTMLElement>()
let tick: ReturnType<typeof setInterval> | undefined

const signedIn = computed(() => !!state.value?.signedIn)
const running = computed(() => state.value?.running ?? null)
const duration = computed(() => running.value ? Math.max(0, now.value + (state.value?.clockOffsetMs ?? 0) - Date.parse(running.value.startedAt)) : 0)
const saved = computed(() => !!state.value && !running.value && state.value.savedAt > 0 && now.value - state.value.savedAt < 2600)
const connection = computed(() => !signedIn.value ? '未登录' : state.value?.ready ? '已连接' : '等待连接')
const title = computed(() => !signedIn.value ? '尚未登录' : running.value?.title ?? state.value?.recent?.title ?? '尚未选择工作')
const subtitle = computed(() => {
  if (!signedIn.value) return '打开主界面完成登录'
  if (!state.value?.ready) return '正在等待连接…'
  if (saved.value) return '已保存本次计时'
  if (running.value) return formatDuration(duration.value)
  return state.value?.recent ? '已暂停 · 可继续上次工作' : '从查找工作项开始计时'
})
const ringIcon = computed(() => !signedIn.value ? 'window' : saved.value ? 'check' : running.value ? 'clock' : state.value?.recent ? 'pause' : 'clock')
const canToggle = computed(() => signedIn.value && !!state.value?.enabled && (!!running.value || !!state.value?.recent))
const display = computed(() => !state.value?.visible ? 'hidden' : state.value.display)
const displays: Array<{ value: 'orb' | 'dock' | 'hidden'; label: string; icon: 'orb' | 'dock' | 'hidden'; action: TimerMenuAction }> = [
  { value: 'orb', label: '悬浮球', icon: 'orb', action: 'show-orb' },
  { value: 'dock', label: '侧边栏', icon: 'dock', action: 'show-dock' },
  { value: 'hidden', label: '隐藏', icon: 'hidden', action: 'hide' },
]

function run(action: TimerMenuAction) { void desktop?.runMenuAction?.(action) }
async function refreshInboxPreferences() {
  if (!inbox) return
  try { toasts.value = (await inbox.getPreferences()).toasts; preferencesReady.value = true } catch { preferencesReady.value = false }
}
async function toggleToasts() {
  if (!inbox || !preferencesReady.value || savingPreferences.value) return
  savingPreferences.value = true
  try { toasts.value = (await inbox.setPreferences({ toasts: !toasts.value })).toasts } catch { await refreshInboxPreferences() }
  finally { savingPreferences.value = false }
}
function items() { return [...(root.value?.querySelectorAll<HTMLButtonElement>('[data-menu-item]:not(:disabled)') ?? [])] }
/** Opening focuses the panel itself so keyboard users can arrow into it without a focus ring greeting mouse users. */
function focusPanel() { void nextTick(() => root.value?.focus()); void refreshInboxPreferences() }
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); run('close'); return }
  const list = items()
  if (!list.length) return
  const index = list.indexOf(document.activeElement as HTMLButtonElement)
  const next = event.key === 'ArrowDown' || event.key === 'ArrowRight' ? index + 1 : event.key === 'ArrowUp' || event.key === 'ArrowLeft' ? (index < 0 ? list.length - 1 : index - 1)
    : event.key === 'Home' ? 0 : event.key === 'End' ? list.length - 1 : undefined
  if (next === undefined) return
  event.preventDefault()
  list[(next + list.length) % list.length]?.focus()
}
const off = desktop?.onMenuState?.(value => {
  const opening = !state.value
  state.value = value
  now.value = Date.now()
  if (opening || !root.value?.contains(document.activeElement)) focusPanel()
})
onMounted(() => {
  tick = setInterval(() => { now.value = Date.now() }, 1000)
  window.addEventListener('focus', focusPanel)
})
onBeforeUnmount(() => { clearInterval(tick); off?.(); window.removeEventListener('focus', focusPanel) })
</script>

<template>
  <div class="quick-menu">
    <div
      ref="root"
      class="menu-card"
      role="menu"
      tabindex="-1"
      aria-label="YumpooPlatform 快捷菜单"
      :class="{ 'is-running': running, 'is-saved': saved }"
      @keydown="keydown"
    >
      <header class="menu-head">
        <img
          class="menu-logo"
          :src="brandLogo"
          alt=""
        >
        <strong>YumpooPlatform</strong>
        <span
          class="menu-connection"
          :class="{ 'is-ready': state?.ready, 'is-signed-out': !signedIn }"
        ><span class="connection-dot" />{{ connection }}</span>
        <button
          class="menu-icon"
          role="menuitem"
          data-menu-item
          aria-label="计时设置"
          :disabled="!signedIn"
          @click="run('settings')"
        >
          <TimerIcon name="settings" />
        </button>
      </header>
      <div class="menu-status">
        <span
          class="status-ring"
          aria-hidden="true"
        ><TimerIcon :name="ringIcon" /></span>
        <div class="status-copy">
          <strong>{{ title }}</strong>
          <span
            class="status-sub"
            role="status"
          >{{ subtitle }}</span>
        </div>
        <button
          v-if="signedIn && (running || state?.recent)"
          class="status-toggle"
          role="menuitem"
          data-menu-item
          :aria-label="running ? '暂停计时' : '继续计时'"
          :disabled="!canToggle"
          @click="run('toggle')"
        >
          <TimerIcon :name="running ? 'pause' : 'play'" />
        </button>
      </div>
      <div class="menu-list">
        <button
          class="menu-item"
          role="menuitem"
          data-menu-item
          @click="run('find')"
        >
          <TimerIcon name="search" /><span>查找工作项…</span>
        </button>
        <button
          class="menu-item"
          role="menuitem"
          data-menu-item
          @click="run('open-main')"
        >
          <TimerIcon name="window" /><span>打开主界面</span>
        </button>
        <div
          v-if="state?.inbox"
          class="menu-inbox"
        >
          <button
            class="menu-item"
            role="menuitem"
            data-menu-item
            @click="run('open-inbox')"
          >
            <Bell /><span>收件箱</span><span class="inbox-count">{{ state.inbox.unreadCount > 99 ? '99+' : state.inbox.unreadCount }}</span>
          </button>
          <button
            v-if="inbox"
            class="inbox-preference"
            role="menuitemcheckbox"
            data-menu-item
            aria-label="桌面通知"
            :aria-checked="toasts"
            :disabled="!preferencesReady || savingPreferences"
            @click="toggleToasts"
          >
            <span>桌面通知</span><span
              class="menu-switch"
              :class="{ 'is-on': toasts }"
              aria-hidden="true"
            ><span /></span>
          </button>
        </div>
        <div
          class="menu-display"
          role="group"
          aria-label="计时器显示方式"
        >
          <span class="display-label">显示</span>
          <div class="display-segment">
            <button
              v-for="option in displays"
              :key="option.value"
              role="menuitemradio"
              data-menu-item
              :aria-checked="display === option.value"
              :class="{ 'is-active': display === option.value }"
              :disabled="!signedIn"
              @click="run(option.action)"
            >
              <TimerIcon :name="option.icon" />{{ option.label }}
            </button>
          </div>
        </div>
        <button
          class="menu-item"
          role="menuitemcheckbox"
          data-menu-item
          :aria-checked="!!state?.pinned"
          :disabled="!signedIn"
          @click="run('toggle-pin')"
        >
          <TimerIcon name="pin" /><span>窗口置顶</span>
          <span
            class="menu-switch"
            :class="{ 'is-on': state?.pinned }"
            aria-hidden="true"
          ><span /></span>
        </button>
      </div>
      <div class="menu-foot">
        <button
          class="menu-item is-exit"
          role="menuitem"
          data-menu-item
          @click="run('exit')"
        >
          <TimerIcon name="power" /><span>退出 YumpooPlatform…</span>
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.quick-menu{box-sizing:border-box;height:100vh;padding:12px;font-family:var(--yp-font-family);font-size:13px;color:var(--yp-text-primary)}
.menu-card{outline:0;box-sizing:border-box;display:flex;flex-direction:column;height:100%;padding:6px;border-radius:var(--yp-timer-radius);border:1px solid var(--yp-timer-hairline);
  background:var(--yp-timer-surface);box-shadow:var(--yp-timer-shadow),var(--yp-timer-highlight);overflow:hidden;user-select:none;animation:menu-in .18s var(--yp-timer-ease) both}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0}
button:disabled{cursor:default;opacity:.45}
button:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:-2px}
.menu-head{display:flex;align-items:center;gap:8px;height:40px;padding:0 4px 0 8px;flex-shrink:0}
.menu-logo{width:20px;height:20px}
.menu-head strong{font-size:13px;font-weight:650;letter-spacing:-.01em}
.menu-connection{display:flex;align-items:center;gap:5px;margin-left:auto;padding:2px 8px;border-radius:999px;font-size:11px;color:var(--yp-text-muted);background:var(--yp-timer-sunken)}
.connection-dot{width:6px;height:6px;border-radius:50%;background:var(--yp-timer-offline)}
.is-ready .connection-dot{background:var(--yp-timer-saved)}.is-signed-out .connection-dot{background:var(--yp-text-disabled)}
.menu-icon{display:grid;place-items:center;width:28px;height:28px;border-radius:8px;color:var(--yp-text-secondary)}
.menu-icon:not(:disabled):hover{background:var(--yp-timer-hover);color:var(--yp-text-primary)}
.menu-icon svg{width:16px;height:16px}
.menu-status{display:flex;align-items:center;gap:10px;margin:2px 2px 6px;padding:10px 10px 10px 12px;border-radius:11px;flex-shrink:0;
  background:var(--yp-timer-sunken);box-shadow:inset 0 0 0 1px var(--yp-timer-hairline)}
.is-running .menu-status{background:linear-gradient(135deg,color-mix(in srgb,var(--yp-timer-accent) 11%,var(--yp-bg-raised)),color-mix(in srgb,var(--yp-timer-accent) 4%,var(--yp-bg-raised)));box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--yp-timer-accent) 18%,transparent)}
.status-ring{display:grid;place-items:center;width:32px;height:32px;border-radius:50%;flex-shrink:0;color:var(--yp-text-muted);box-shadow:inset 0 0 0 2px var(--yp-timer-hairline)}
.status-ring svg{width:15px;height:15px}
.is-running .status-ring{color:var(--yp-timer-accent);box-shadow:inset 0 0 0 2px color-mix(in srgb,var(--yp-timer-accent) 55%,transparent)}
.is-saved .status-ring{color:var(--yp-timer-saved);box-shadow:inset 0 0 0 2px color-mix(in srgb,var(--yp-timer-saved) 55%,transparent)}
.status-copy{display:flex;flex-direction:column;gap:2px;min-width:0;flex:1}
.status-copy strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;font-weight:600}
.status-sub{font-size:11.5px;color:var(--yp-text-muted);font-variant-numeric:tabular-nums}
.is-running .status-sub{font-size:13px;font-weight:600;color:var(--yp-timer-accent)}
.is-saved .status-sub{color:var(--yp-timer-saved)}
.status-toggle{display:grid;place-items:center;width:32px;height:32px;border-radius:50%;flex-shrink:0;background:var(--yp-timer-accent);color:#fff;box-shadow:0 3px 10px -2px color-mix(in srgb,var(--yp-timer-accent) 50%,transparent);transition:transform .16s ease}
.is-running .status-toggle{background:var(--yp-bg-raised);color:var(--yp-timer-accent);box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--yp-timer-accent) 25%,transparent)}
.status-toggle svg{width:14px;height:14px}
.status-toggle:not(:disabled):active{transform:scale(.92)}
.status-toggle:focus-visible{outline-offset:2px}
.menu-list{display:flex;flex-direction:column;gap:1px;padding:5px 0;border-top:1px solid var(--yp-timer-hairline);flex:1}
.menu-item{display:flex;align-items:center;gap:10px;width:100%;height:36px;padding:0 10px;border-radius:8px;text-align:left;color:var(--yp-text-primary);transition:background .12s ease}
.menu-item svg{width:16px;height:16px;color:var(--yp-text-secondary)}
.menu-item:not(:disabled):hover,.menu-item:focus-visible{background:var(--yp-timer-hover)}
.menu-item:focus-visible{outline:0}
.menu-inbox{display:flex;align-items:center;gap:4px;height:39px;flex-shrink:0}
.menu-inbox .menu-item{flex:1;min-width:0;gap:6px}
.inbox-count{font-size:11px;color:var(--yp-text-muted);font-variant-numeric:tabular-nums}
.inbox-preference{display:flex;align-items:center;gap:6px;padding:8px 8px 8px 4px;font-size:11px;color:var(--yp-text-secondary);border-radius:8px}
.inbox-preference:not(:disabled):hover{background:var(--yp-timer-hover)}
.menu-display{display:flex;align-items:center;gap:10px;height:40px;padding:0 6px 0 10px}
.display-label{padding-left:26px;font-size:13px;color:var(--yp-text-primary);flex-shrink:0;white-space:nowrap}
.display-segment{display:flex;flex:1;padding:2px;border-radius:9px;background:var(--yp-timer-sunken);box-shadow:inset 0 0 0 1px var(--yp-timer-hairline)}
.display-segment button{display:flex;align-items:center;justify-content:center;gap:4px;flex:1;height:26px;border-radius:7px;font-size:11.5px;color:var(--yp-text-secondary);transition:background .15s ease,color .15s ease,box-shadow .15s ease}
.display-segment svg{width:13px;height:13px}
.display-segment button.is-active{background:var(--yp-timer-chip);color:var(--yp-text-primary);font-weight:600;box-shadow:var(--yp-timer-shadow-soft)}
.display-segment button.is-active svg{color:var(--yp-timer-accent)}
.menu-switch{position:relative;margin-left:auto;width:30px;height:17px;border-radius:999px;background:var(--yp-border-default);transition:background .18s ease}
.menu-switch span{position:absolute;left:2px;top:2px;width:13px;height:13px;border-radius:50%;background:#fff;box-shadow:0 1px 2px color-mix(in srgb,#0b1220 30%,transparent);transition:transform .18s var(--yp-timer-ease)}
.menu-switch.is-on{background:var(--yp-timer-accent)}.menu-switch.is-on span{transform:translateX(13px)}
.menu-foot{padding-top:5px;border-top:1px solid var(--yp-timer-hairline);flex-shrink:0}
.is-exit:hover svg,.is-exit:focus-visible svg{color:var(--yp-status-red)}
@keyframes menu-in{from{opacity:0;transform:translateY(4px) scale(.98)}to{opacity:1;transform:none}}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
</style>
