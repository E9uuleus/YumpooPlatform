<script setup lang="ts">
import { TopRight } from '@element-plus/icons-vue'
import { ElIcon } from 'element-plus'
import type { Component } from 'vue'

export type JumpTone = 'blue' | 'purple' | 'teal' | 'red' | 'green' | 'amber'

withDefaults(defineProps<{
  label: string
  meta: string
  icon: Component
  tone?: JumpTone
  index?: number
}>(), { tone: 'blue', index: 0 })

defineEmits<{ open: [] }>()

function spotlight(event: PointerEvent): void {
  const card = event.currentTarget as HTMLElement, bounds = card.getBoundingClientRect()
  card.style.setProperty('--spot-x', `${event.clientX - bounds.left}px`)
  card.style.setProperty('--spot-y', `${event.clientY - bounds.top}px`)
}
</script>

<template>
  <button
    type="button"
    class="yp-jump-card"
    :class="`yp-jump-card--${tone}`"
    :style="{ '--i': index }"
    @pointermove="spotlight"
    @click="$emit('open')"
  >
    <span class="yp-jump-card__icon"><el-icon aria-hidden="true"><component :is="icon" /></el-icon></span>
    <el-icon
      class="yp-jump-card__arrow"
      aria-hidden="true"
    >
      <top-right />
    </el-icon>
    <strong>{{ label }}</strong>
    <span class="yp-jump-card__meta">{{ meta }}</span>
  </button>
</template>

<style scoped>
.yp-jump-card {
  --from: var(--yp-action-primary);
  --to: var(--yp-label-bright-blue);

  position: relative;
  display: grid;
  justify-items: start;
  gap: 6px;
  min-width: 0;
  padding: var(--yp-space-4);
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--yp-border-subtle) 80%, transparent);
  border-radius: var(--yp-radius-xl);
  background:
    radial-gradient(160px circle at var(--spot-x, -200px) var(--spot-y, -200px), color-mix(in srgb, var(--from) 12%, transparent), transparent 70%),
    color-mix(in srgb, var(--yp-bg-surface) 76%, transparent);
  box-shadow: var(--yp-shadow-card);
  color: var(--yp-text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  backdrop-filter: blur(14px) saturate(1.3);
  animation: yp-jump-rise 320ms var(--yp-ease-standard) backwards;
  animation-delay: calc(var(--i) * 40ms + 80ms);
  transition:
    transform var(--yp-motion-overlay) var(--yp-ease-standard),
    box-shadow var(--yp-motion-overlay) var(--yp-ease-standard),
    border-color var(--yp-motion-overlay) var(--yp-ease-standard);
}

.yp-jump-card:hover {
  border-color: color-mix(in srgb, var(--from) 35%, var(--yp-border-subtle));
  box-shadow: var(--yp-shadow-hover);
  transform: translateY(-2px);
}

.yp-jump-card:active {
  transform: scale(0.98);
}

.yp-jump-card:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 2px;
}

.yp-jump-card--purple {
  --from: var(--yp-label-dark-purple);
  --to: var(--yp-label-lavender);
}

.yp-jump-card--teal {
  --from: var(--yp-status-teal);
  --to: var(--yp-label-aquamarine);
}

.yp-jump-card--red {
  --from: var(--yp-label-red);
  --to: var(--yp-label-sunset);
}

.yp-jump-card--green {
  --from: var(--yp-status-green);
  --to: var(--yp-label-bright-green);
}

.yp-jump-card--amber {
  --from: var(--yp-label-dark-orange);
  --to: var(--yp-label-egg-yolk);
}

.yp-jump-card__icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--from), var(--to));
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, var(--yp-status-blue-foreground) 35%, transparent),
    0 6px 14px -4px color-mix(in srgb, var(--from) 55%, transparent);
  color: var(--yp-status-blue-foreground);
  font-size: 18px;
}

.yp-jump-card__arrow {
  position: absolute;
  top: var(--yp-space-4);
  right: var(--yp-space-4);
  color: var(--yp-text-muted);
  opacity: 0;
  translate: -4px 4px;
  transition:
    opacity var(--yp-motion-overlay) var(--yp-ease-standard),
    translate var(--yp-motion-overlay) var(--yp-ease-standard);
}

.yp-jump-card:hover .yp-jump-card__arrow,
.yp-jump-card:focus-visible .yp-jump-card__arrow {
  opacity: 1;
  translate: none;
}

.yp-jump-card strong {
  font-size: 14px;
  font-weight: 600;
}

.yp-jump-card__meta {
  display: flex;
  align-items: flex-end;
  gap: var(--yp-space-2);
  color: var(--yp-text-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
@keyframes yp-jump-rise {
  from { opacity: 0; transform: translateY(10px); }
}

@media (prefers-reduced-motion: reduce) {
  .yp-jump-card { animation: none; }
  .yp-jump-card:hover,
  .yp-jump-card:active { transform: none; }
}
</style>
