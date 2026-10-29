<script setup lang="ts">
import { CircleCheckFilled, CircleCloseFilled, InfoFilled, WarningFilled } from '@element-plus/icons-vue'
import { ElIcon } from 'element-plus'

export type NoticeTone = 'info' | 'success' | 'warning' | 'danger'

withDefaults(defineProps<{
  tone?: NoticeTone
  /** 一句结论；只有一句话的提醒不写标题，直接放正文。 */
  title?: string | undefined
  size?: 'default' | 'small'
}>(), {
  tone: 'info',
  size: 'default',
})

const ICONS = { info: InfoFilled, success: CircleCheckFilled, warning: WarningFilled, danger: CircleCloseFilled } as const
</script>

<template>
  <div
    class="yp-notice"
    :class="[`yp-notice--${tone}`, `yp-notice--${size}`]"
    :role="tone === 'danger' ? 'alert' : 'status'"
  >
    <el-icon
      class="yp-notice__icon"
      aria-hidden="true"
    >
      <component :is="ICONS[tone]" />
    </el-icon>
    <div class="yp-notice__content">
      <p
        v-if="title"
        class="yp-notice__title"
      >
        {{ title }}
      </p>
      <div
        v-if="$slots.default"
        class="yp-notice__body"
      >
        <slot />
      </div>
    </div>
    <div
      v-if="$slots.actions"
      class="yp-notice__actions"
    >
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.yp-notice {
  --yp-notice-tone: var(--yp-status-blue);
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: var(--yp-space-2) var(--yp-space-3);
  padding: var(--yp-space-3) var(--yp-space-4) var(--yp-space-3) calc(var(--yp-space-4) + 3px);
  border: 1px solid color-mix(in srgb, var(--yp-notice-tone) var(--yp-status-border-mix), var(--yp-border-subtle));
  border-radius: var(--yp-radius-md);
  color: var(--yp-text-primary);
  background: linear-gradient(90deg,
    color-mix(in srgb, var(--yp-notice-tone) var(--yp-status-surface-mix), var(--yp-bg-surface)),
    color-mix(in srgb, var(--yp-notice-tone) 4%, var(--yp-bg-surface)));
  box-shadow: inset 3px 0 0 var(--yp-notice-tone);
  font: 400 var(--yp-type-body-size) / var(--yp-type-body-line) var(--yp-font-family);
}

.yp-notice--success { --yp-notice-tone: var(--yp-status-green); }
.yp-notice--warning { --yp-notice-tone: var(--yp-status-orange); }
.yp-notice--danger { --yp-notice-tone: var(--yp-status-red); }

.yp-notice__icon {
  width: 22px;
  height: 22px;
  flex: none;
  border-radius: var(--yp-radius-pill);
  color: var(--yp-notice-tone);
  background: color-mix(in srgb, var(--yp-notice-tone) 18%, transparent);
  font-size: 15px;
}

.yp-notice__content {
  display: grid;
  flex: 1 1 240px;
  min-width: 0;
  gap: 2px;
}

.yp-notice__title {
  margin: 0;
  color: color-mix(in srgb, var(--yp-notice-tone) 62%, var(--yp-text-primary));
  font-weight: 600;
}

.yp-notice__body { overflow-wrap: anywhere; }
.yp-notice__title + .yp-notice__body { color: var(--yp-text-secondary); }
:slotted(strong) { color: var(--yp-text-primary); font-weight: 600; }
:slotted(p) { margin: 0; }
:slotted(p:not(:first-child)) { margin-top: var(--yp-space-1); }
:slotted(ul) { margin: var(--yp-space-1) 0 0; padding-left: 1.25em; }

.yp-notice__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--yp-space-2);
  margin-left: auto;
}

.yp-notice--small {
  gap: var(--yp-space-2);
  padding: 6px var(--yp-space-3) 6px calc(var(--yp-space-3) + 3px);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

.yp-notice--small .yp-notice__icon { width: 18px; height: 18px; font-size: 12px; }
.yp-notice--small .yp-notice__content { flex-basis: 160px; }
</style>
