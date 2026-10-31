<script setup lang="ts">
import { ArrowRight, TopRight } from '@element-plus/icons-vue'
import { ElIcon } from 'element-plus'
import type { Component } from 'vue'

export type JumpTone = 'blue' | 'purple' | 'teal' | 'red' | 'green' | 'amber'

withDefaults(defineProps<{
  label: string
  meta: string
  icon: Component
  tone?: JumpTone
  index?: number
  variant?: 'tile' | 'row'
}>(), { tone: 'blue', index: 0, variant: 'tile' })

defineEmits<{ open: [] }>()

</script>

<template>
  <button
    type="button"
    class="yp-jump-card"
    :class="[`yp-jump-card--${tone}`, `yp-jump-card--${variant}`]"
    :style="{ '--i': index }"
    @click="$emit('open')"
  >
    <span class="yp-jump-card__icon"><el-icon aria-hidden="true"><component :is="icon" /></el-icon></span>
    <el-icon
      class="yp-jump-card__arrow"
      aria-hidden="true"
    >
      <arrow-right v-if="variant === 'row'" />
      <top-right v-else />
    </el-icon>
    <strong>{{ label }}</strong>
    <span class="yp-jump-card__meta">{{ meta }}</span>
  </button>
</template>

<style scoped>
.yp-jump-card {
  --from: var(--yp-action-primary);

  position: relative;
  display: grid;
  justify-items: start;
  gap: 6px;
  min-width: 0;
  padding: var(--yp-space-4);
  overflow: hidden;
  border: 0;
  border-radius: var(--yp-radius-xl);
  background: var(--yp-bg-sunken);
  color: var(--yp-text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
  animation: yp-jump-rise 320ms var(--yp-ease-standard) backwards;
  animation-delay: calc(var(--i) * 40ms + 80ms);
  transition: background-color var(--yp-motion-fast) var(--yp-ease-standard);
}

.yp-jump-card:hover {
  background: var(--yp-bg-hover);
}



.yp-jump-card:focus-visible {
  outline: 2px solid var(--yp-focus-ring);
  outline-offset: 2px;
}

.yp-jump-card--purple {
  --from: var(--yp-label-dark-purple);
}

.yp-jump-card--teal {
  --from: var(--yp-status-teal);
}

.yp-jump-card--red {
  --from: var(--yp-label-red);
}

.yp-jump-card--green {
  --from: var(--yp-status-green);
}

.yp-jump-card--amber {
  --from: var(--yp-label-dark-orange);
}

.yp-jump-card__icon {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 10px;
  background: var(--from);
  color: var(--yp-on-solid);
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

.yp-jump-card--row {
  grid-template-columns: auto minmax(0, 1fr) auto;
  grid-template-areas:
    "icon label arrow"
    "icon meta arrow";
  align-items: center;
  column-gap: var(--yp-space-3);
  row-gap: 2px;
  padding: var(--yp-space-3);
  border-radius: var(--yp-radius-lg);
}

.yp-jump-card--row .yp-jump-card__icon {
  grid-area: icon;
  width: 34px;
  height: 34px;
  background: color-mix(in srgb, var(--from) 14%, var(--yp-bg-surface));
  color: var(--from);
}

.yp-jump-card--row strong {
  grid-area: label;
}

.yp-jump-card--row .yp-jump-card__meta {
  grid-area: meta;
}

.yp-jump-card--row .yp-jump-card__arrow {
  position: static;
  grid-area: arrow;
  opacity: 1;
  translate: none;
  transition:
    color var(--yp-motion-overlay) var(--yp-ease-standard),
    translate var(--yp-motion-overlay) var(--yp-ease-standard);
}

.yp-jump-card--row:hover .yp-jump-card__arrow,
.yp-jump-card--row:focus-visible .yp-jump-card__arrow {
  color: var(--from);
  translate: 2px 0;
}

@keyframes yp-jump-rise {
  from { opacity: 0; transform: translateY(10px); }
}

@media (prefers-reduced-motion: reduce) {
  .yp-jump-card { animation: none; }
  .yp-jump-card--row:hover .yp-jump-card__arrow { translate: none; }
}
</style>
