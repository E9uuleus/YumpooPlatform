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
  --yp-notice-bg: var(--yp-message-info-bg);
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: var(--yp-space-2) var(--yp-space-3);
  padding: 10px var(--yp-space-4);
  border-radius: var(--yp-radius-md);
  color: var(--yp-on-solid);
  background: var(--yp-notice-bg);
  font: 400 var(--yp-type-body-size) / var(--yp-type-body-line) var(--yp-font-family);
}

.yp-notice--success { --yp-notice-bg: var(--yp-message-success-bg); }
.yp-notice--warning { --yp-notice-bg: var(--yp-message-warning-bg); }
.yp-notice--danger { --yp-notice-bg: var(--yp-message-error-bg); }

.yp-notice__icon {
  width: 22px;
  height: 22px;
  flex: none;
  font-size: 18px;
}

.yp-notice__content {
  display: grid;
  flex: 1 1 240px;
  min-width: 0;
  gap: 2px;
}

.yp-notice__title {
  margin: 0;
  font-weight: 600;
}

.yp-notice__body { overflow-wrap: anywhere; }
:slotted(strong) { font-weight: 600; }
:slotted(p) { margin: 0; }
:slotted(p:not(:first-child)) { margin-top: var(--yp-space-1); }
:slotted(ul) { margin: var(--yp-space-1) 0 0; padding-left: 1.25em; }
:slotted(a) { color: inherit; text-decoration: underline; text-underline-offset: 2px; }

.yp-notice__actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: var(--yp-space-2);
  margin-left: auto;
}

/* 实底上的按钮统一白色前景：默认按钮为半透明白底，text / link 按钮为白字。 */
.yp-notice :deep(.el-button) {
  --el-button-text-color: var(--yp-on-solid);
  --el-button-bg-color: color-mix(in srgb, var(--yp-on-solid) 18%, transparent);
  --el-button-hover-text-color: var(--yp-on-solid);
  --el-button-hover-bg-color: color-mix(in srgb, var(--yp-on-solid) 28%, transparent);
  --el-button-active-text-color: var(--yp-on-solid);
  --el-button-active-bg-color: color-mix(in srgb, var(--yp-on-solid) 34%, transparent);
  --el-button-disabled-text-color: color-mix(in srgb, var(--yp-on-solid) 60%, transparent);
  --el-button-disabled-bg-color: color-mix(in srgb, var(--yp-on-solid) 12%, transparent);
}

.yp-notice :deep(.el-button.is-link),
.yp-notice :deep(.el-button.is-text) {
  color: var(--yp-on-solid);
}

.yp-notice :deep(.el-button.is-link:not(.is-disabled):hover),
.yp-notice :deep(.el-button.is-text:not(.is-disabled):hover) {
  color: var(--yp-on-solid);
  background: color-mix(in srgb, var(--yp-on-solid) 18%, transparent);
}

.yp-notice--small {
  gap: var(--yp-space-2);
  padding: 6px var(--yp-space-3);
  font-size: var(--yp-type-caption-size);
  line-height: var(--yp-type-caption-line);
}

.yp-notice--small .yp-notice__icon { width: 18px; height: 18px; font-size: 14px; }
.yp-notice--small .yp-notice__content { flex-basis: 160px; }
</style>
