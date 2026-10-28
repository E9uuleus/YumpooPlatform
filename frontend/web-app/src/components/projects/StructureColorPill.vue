<script setup lang="ts">
import type { WorkItemLabelColorToken } from '@yumpoo/api-client'
import { ElPopover } from 'element-plus'
import { mondayWorkItemLabelColors, workItemLabelColorValue } from './workItemLabelColors'

/** One label of the initial structure; its colour opens the label palette before the project exists. */
defineProps<{ name: string; disabled?: boolean }>()
const color = defineModel<WorkItemLabelColorToken>('color', { required: true })
</script>

<template>
  <el-popover
    placement="bottom-start"
    :width="184"
    trigger="click"
    :disabled="disabled"
    popper-class="structure-color-popover"
  >
    <template #reference>
      <button
        type="button"
        class="structure-color-pill"
        :style="{ background: workItemLabelColorValue(color) }"
        :disabled="disabled"
        :aria-label="`调整「${name}」的颜色`"
      >
        <span>{{ name }}</span>
        <svg
          class="structure-color-pill__icon"
          viewBox="0 0 16 16"
          width="12"
          height="12"
          fill="none"
          stroke="currentColor"
          stroke-width="1.6"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path d="M12.5 5.5l-4-4-5.5 5.5a1.41 1.41 0 000 2l3.5 3.5a1.41 1.41 0 002 0l4-4z" />
          <path
            d="M13.5 11c0 1.1-.9 2-2 2s-2-.9-2-2c0-.8 2-3 2-3s2 2.2 2 3z"
            fill="currentColor"
          />
        </svg>
      </button>
    </template>
    <div
      class="structure-color-grid"
      role="listbox"
      :aria-label="`${name}的颜色`"
    >
      <button
        v-for="option in mondayWorkItemLabelColors"
        :key="option.token"
        type="button"
        role="option"
        class="structure-color-grid__swatch"
        :class="{ 'is-selected': option.token === color }"
        :aria-selected="option.token === color"
        :style="{ background: option.color }"
        :title="option.label"
        @click="color = option.token"
      />
    </div>
  </el-popover>
</template>

<style scoped>
.structure-color-pill { display: inline-flex; align-items: center; gap: 6px; height: 26px; padding: 0 10px; border: 0; border-radius: var(--yp-radius-pill); color: var(--yp-text-inverse); font: 500 12px var(--yp-font-family); white-space: nowrap; cursor: pointer; transition: box-shadow var(--yp-motion-fast) var(--yp-ease-standard); }
.structure-color-pill:hover:not(:disabled) { box-shadow: 0 0 0 2px var(--yp-bg-raised), 0 0 0 3.5px var(--yp-border-strong); }
.structure-color-pill:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: 2px; }
.structure-color-pill:disabled { cursor: default; }
.structure-color-pill__icon { opacity: .6; transition: opacity var(--yp-motion-fast) var(--yp-ease-standard); }
.structure-color-pill:hover .structure-color-pill__icon, .structure-color-pill:focus-visible .structure-color-pill__icon { opacity: 1; }
.structure-color-grid { display: grid; grid-template-columns: repeat(6, 22px); gap: 6px; }
.structure-color-grid__swatch { width: 22px; height: 22px; padding: 0; border: 0; border-radius: 6px; cursor: pointer; transition: transform var(--yp-motion-fast) var(--yp-ease-standard); }
.structure-color-grid__swatch:hover { transform: scale(1.12); }
.structure-color-grid__swatch.is-selected { box-shadow: 0 0 0 2px var(--yp-bg-raised), 0 0 0 4px var(--yp-focus-ring); }
.structure-color-grid__swatch:focus-visible { outline: 2px solid var(--yp-focus-ring); outline-offset: 2px; }
:global(.structure-color-popover.el-popover.el-popper) { padding: 10px; border-radius: var(--yp-radius-md); }
</style>
