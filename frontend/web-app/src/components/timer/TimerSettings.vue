<script setup lang="ts">
import type { TimerDisplayStyle, TimerDockSide, TimerOrbSize, TimerPreferences, TimerPreferencesChange } from '@yumpoo/preload-contract'
import TimerIcon from './TimerIcon.vue'

defineProps<{ preferences: TimerPreferences; pinned: boolean }>()
const emit = defineEmits<{ update: [change: TimerPreferencesChange]; pin: [] }>()

const displays: Array<{ value: TimerDisplayStyle; label: string; hint: string }> = [
  { value: 'orb', label: '悬浮球', hint: '可拖到任意位置的迷你计时球' },
  { value: 'dock', label: '侧边栏', hint: '贴在屏幕边缘，悬停展开' },
]
const sizes: Array<{ value: TimerOrbSize; label: string }> = [{ value: 'small', label: '小' }, { value: 'medium', label: '中' }, { value: 'large', label: '大' }]
const sides: Array<{ value: TimerDockSide; label: string }> = [{ value: 'left', label: '左侧' }, { value: 'right', label: '右侧' }]
</script>

<template>
  <div class="timer-settings">
    <section class="settings-group">
      <h3 id="timer-settings-display">
        显示方式
      </h3>
      <div
        class="display-options"
        role="radiogroup"
        aria-labelledby="timer-settings-display"
      >
        <button
          v-for="option in displays"
          :key="option.value"
          class="display-option"
          :class="{ 'is-selected': preferences.display === option.value }"
          role="radio"
          :aria-checked="preferences.display === option.value"
          @click="emit('update', { display: option.value })"
        >
          <span
            class="display-art"
            :data-art="option.value"
            aria-hidden="true"
          ><span class="art-window" /><span class="art-surface" /></span>
          <strong>{{ option.label }}</strong>
          <span class="display-hint">{{ option.hint }}</span>
          <span
            class="display-check"
            aria-hidden="true"
          ><TimerIcon name="check" /></span>
        </button>
      </div>
    </section>
    <section
      v-if="preferences.display === 'orb'"
      class="settings-row"
    >
      <span
        id="timer-settings-size"
        class="row-label"
      >悬浮球大小</span>
      <div
        class="segmented"
        role="radiogroup"
        aria-labelledby="timer-settings-size"
      >
        <button
          v-for="size in sizes"
          :key="size.value"
          role="radio"
          :aria-checked="preferences.orbSize === size.value"
          :class="{ 'is-active': preferences.orbSize === size.value }"
          @click="emit('update', { orbSize: size.value })"
        >
          {{ size.label }}
        </button>
      </div>
    </section>
    <section
      v-else
      class="settings-row"
    >
      <span
        id="timer-settings-side"
        class="row-label"
      >停靠位置</span>
      <div
        class="segmented"
        role="radiogroup"
        aria-labelledby="timer-settings-side"
      >
        <button
          v-for="side in sides"
          :key="side.value"
          role="radio"
          :aria-checked="preferences.dockSide === side.value"
          :class="{ 'is-active': preferences.dockSide === side.value }"
          @click="emit('update', { dockSide: side.value })"
        >
          {{ side.label }}
        </button>
      </div>
    </section>
    <section class="settings-row">
      <span class="row-label">窗口置顶<small>始终显示在其他窗口上方</small></span>
      <button
        class="switch"
        role="switch"
        aria-label="窗口置顶"
        :aria-checked="pinned"
        :class="{ 'is-on': pinned }"
        @click="emit('pin')"
      >
        <span class="switch-thumb" />
      </button>
    </section>
    <p class="settings-hint">
      右键悬浮球、侧边栏或托盘图标可打开快捷菜单；设置会保存在本机。
    </p>
  </div>
</template>

<style scoped>
.timer-settings{display:flex;flex-direction:column;gap:6px;padding:4px 14px 14px;overflow:auto;flex:1;min-height:0}
button{font:inherit;color:inherit;cursor:pointer;border:0;background:none;padding:0}
button:focus-visible{outline:2px solid var(--yp-timer-accent);outline-offset:2px}
h3{margin:6px 2px 10px;font-size:11px;font-weight:600;letter-spacing:.02em;color:var(--yp-text-muted)}
.display-options{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.display-option{position:relative;display:flex;flex-direction:column;align-items:flex-start;gap:3px;padding:10px 10px 11px;border-radius:12px;text-align:left;
  border:1px solid var(--yp-timer-hairline);background:var(--yp-bg-raised);transition:border-color .18s ease,box-shadow .18s ease,transform .18s ease}
.display-option:hover{border-color:color-mix(in srgb,var(--yp-timer-accent) 35%,var(--yp-timer-hairline))}
.display-option.is-selected{border-color:var(--yp-timer-accent);box-shadow:0 0 0 3px color-mix(in srgb,var(--yp-timer-accent) 14%,transparent)}
.display-option strong{margin-top:6px;font-size:13px;font-weight:600}
.display-hint{font-size:11px;line-height:15px;color:var(--yp-text-muted)}
.display-check{position:absolute;top:8px;right:8px;display:grid;place-items:center;width:18px;height:18px;border-radius:50%;background:var(--yp-timer-accent);color:#fff;opacity:0;transform:scale(.6);transition:opacity .18s ease,transform .18s var(--yp-timer-ease)}
.display-check svg{width:11px;height:11px}
.is-selected .display-check{opacity:1;transform:none}
.display-art{position:relative;display:block;width:100%;height:64px;border-radius:8px;overflow:hidden;
  background:linear-gradient(160deg,color-mix(in srgb,var(--yp-timer-accent) 7%,var(--yp-timer-sunken)),var(--yp-timer-sunken))}
.art-window{position:absolute;left:10px;top:10px;width:46%;height:28px;border-radius:5px;background:var(--yp-bg-raised);box-shadow:0 1px 2px color-mix(in srgb,#0b1220 10%,transparent)}
.art-window::after{content:'';position:absolute;left:6px;right:10px;top:8px;height:3px;border-radius:2px;background:var(--yp-timer-hairline);box-shadow:0 7px 0 var(--yp-timer-hairline)}
.art-surface{position:absolute;background:var(--yp-bg-raised);box-shadow:0 2px 6px color-mix(in srgb,#0b1220 16%,transparent)}
[data-art=orb] .art-surface{right:12px;bottom:9px;width:20px;height:20px;border-radius:50%;border:2px solid var(--yp-timer-accent);box-sizing:border-box}
[data-art=dock] .art-surface{right:0;top:50%;width:9px;height:26px;margin-top:-13px;border-radius:5px 0 0 5px;border:1px solid var(--yp-timer-hairline);border-right:0;box-sizing:border-box}
[data-art=dock] .art-surface::before{content:'';position:absolute;left:3px;top:6px;width:3px;height:3px;border-radius:50%;background:var(--yp-timer-accent)}
.settings-row{display:flex;align-items:center;justify-content:space-between;gap:12px;min-height:44px;padding:4px 2px;border-bottom:1px solid var(--yp-timer-hairline)}
.settings-row:last-of-type{border-bottom:0}
.row-label{display:flex;flex-direction:column;gap:2px;font-size:12.5px;font-weight:550;color:var(--yp-text-primary)}
.row-label small{font-size:11px;font-weight:400;color:var(--yp-text-muted)}
.segmented{display:flex;padding:2px;border-radius:9px;background:var(--yp-timer-sunken);box-shadow:inset 0 0 0 1px var(--yp-timer-hairline)}
.segmented button{min-width:44px;height:26px;padding:0 10px;border-radius:7px;font-size:12px;color:var(--yp-text-secondary);transition:background .16s ease,color .16s ease,box-shadow .16s ease}
.segmented button.is-active{background:var(--yp-timer-chip);color:var(--yp-text-primary);font-weight:600;box-shadow:var(--yp-timer-shadow-soft)}
.switch{position:relative;width:36px;height:20px;border-radius:999px;background:var(--yp-border-default);transition:background .18s ease;flex-shrink:0}
.switch.is-on{background:var(--yp-timer-accent)}
.switch-thumb{position:absolute;left:2px;top:2px;width:16px;height:16px;border-radius:50%;background:#fff;box-shadow:0 1px 3px color-mix(in srgb,#0b1220 30%,transparent);transition:transform .2s var(--yp-timer-ease)}
.switch.is-on .switch-thumb{transform:translateX(16px)}
.settings-hint{margin:auto 2px 0;padding-top:10px;font-size:11px;line-height:16px;color:var(--yp-text-muted)}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important}}
</style>
