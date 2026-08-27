<script setup lang="ts">
import { ElTooltip } from 'element-plus'
import { computed } from 'vue'

type SortDirection = 'ASC' | 'DESC'

const props = defineProps<{
  label: string
  direction: SortDirection | undefined
  saving: boolean
}>()

const emit = defineEmits<{
  sort: []
  clear: []
  save: []
}>()

const active = computed(() => Boolean(props.direction))
const sortLabel = computed(() => props.direction === 'ASC'
  ? `将${props.label}切换为降序`
  : props.direction === 'DESC'
    ? `将${props.label}切换为升序`
    : `按${props.label}升序排列`)
const tooltipText = computed(() => props.direction === 'ASC'
  ? `${props.label}：当前升序，点击切换为降序`
  : props.direction === 'DESC'
    ? `${props.label}：当前降序，点击切换为升序`
    : `按${props.label}排序`)
</script>

<template>
  <div
    class="monday-column-quick-sort"
    :class="{
      'monday-column-quick-sort--active': active,
      'monday-column-quick-sort--asc': direction === 'ASC',
      'monday-column-quick-sort--desc': direction === 'DESC',
    }"
  >
    <span class="monday-column-quick-sort__label">{{ label }}</span>
    <div
      class="sort-by-column"
      :class="{
        'sort-by-column--active': active,
        'sort-by-column--asc': direction === 'ASC',
        'sort-by-column--desc': direction === 'DESC',
      }"
      role="group"
      :aria-label="`${label}快捷排序`"
    >
      <span v-if="active" class="clear-button-wrapper">
        <button
          type="button"
          class="clear-button"
          :aria-label="`清除${label}排序`"
          :disabled="saving"
          @pointerdown.stop
          @click.stop="emit('clear')"
        >
          清除
        </button>
      </span>
      <span v-if="active" class="save-button-wrapper">
        <button
          type="button"
          class="save-button"
          :aria-label="`保存${label}排序后的工作项顺序`"
          :disabled="saving"
          @pointerdown.stop
          @click.stop="emit('save')"
        >
          {{ saving ? '保存中' : '保存' }}
        </button>
      </span>
      <el-tooltip
        :content="tooltipText"
        placement="top"
        :show-after="320"
        popper-class="monday-sort-tooltip"
      >
        <button
          type="button"
          class="sort-trigger"
          :aria-label="sortLabel"
          :aria-pressed="active"
          :disabled="saving"
          @pointerdown.stop
          @click.stop="emit('sort')"
        >
          <span class="sort-button">
            <svg
              class="sort-icon-svg"
              viewBox="0 0 10 12"
              width="10"
              height="12"
              fill="none"
              xmlns="http://www.w3.org/2000/svg"
              aria-hidden="true"
            >
              <path
                class="icon asc-icon"
                d="M5 1.8L8.8 5.2H1.2L5 1.8Z"
              />
              <path
                class="icon desc-icon"
                d="M1.2 6.8H8.8L5 10.2L1.2 6.8Z"
              />
            </svg>
          </span>
        </button>
      </el-tooltip>
    </div>
  </div>
</template>

<style scoped>
.monday-column-quick-sort {
  position: relative;
  display: flex;
  width: 100%;
  height: 38px;
  align-items: center;
  justify-content: center;
  overflow: visible;
}

.monday-column-quick-sort__label {
  max-width: calc(100% - 12px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sort-by-column {
  position: absolute;
  z-index: 12;
  top: 0;
  left: 50%;
  width: 22px;
  height: 22px;
  opacity: 0;
  pointer-events: none;
  transform: translate(-50%, calc(-50% + 2px));
  transition: opacity 120ms ease, transform 120ms ease;
}

.monday-column-quick-sort:hover .sort-by-column,
.monday-column-quick-sort:focus-within .sort-by-column,
.sort-by-column--active {
  opacity: 1;
  pointer-events: auto;
  transform: translate(-50%, -50%);
}

.sort-trigger {
  position: absolute;
  z-index: 3;
  inset: 0;
  display: flex;
  width: 22px;
  height: 22px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid var(--yp-border-subtle);
  border-radius: 50%;
  color: var(--yp-text-primary);
  background: var(--yp-bg-surface);
  cursor: pointer;
  box-shadow: 0 1px 3px color-mix(in srgb, var(--yp-text-primary) 12%, transparent);
  transition: color 120ms ease, background 120ms ease, border-color 120ms ease, box-shadow 120ms ease;
}

.sort-trigger:hover,
.sort-trigger:focus-visible {
  color: var(--yp-text-inverse);
  background: var(--yp-action-primary);
  border-color: transparent;
  outline: none;
  box-shadow: 0 2px 6px color-mix(in srgb, var(--yp-action-primary) 35%, transparent);
}

.sort-by-column--active .sort-trigger {
  color: var(--yp-text-inverse);
  background: var(--yp-action-primary);
  border-color: transparent;
  box-shadow: 0 2px 6px color-mix(in srgb, var(--yp-action-primary) 35%, transparent);
}

.sort-by-column--active .sort-trigger:hover,
.sort-by-column--active .sort-trigger:focus-visible {
  box-shadow: 0 2px 8px color-mix(in srgb, var(--yp-action-primary) 45%, transparent);
}

.sort-button {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
}

.sort-icon-svg {
  display: block;
  width: 10px;
  height: 12px;
}

.asc-icon,
.desc-icon {
  fill: currentColor;
  transition: fill 120ms ease, opacity 120ms ease;
}

/* 默认状态（未激活，鼠标未落在按钮上：深灰黑色实心三角） */
.sort-trigger .asc-icon,
.sort-trigger .desc-icon {
  fill: var(--yp-text-primary);
  opacity: 0.9;
}

/* 鼠标落在按钮上（图2：纯白色镂空双三角） */
.sort-trigger:hover .asc-icon,
.sort-trigger:hover .desc-icon,
.sort-trigger:focus-visible .asc-icon,
.sort-trigger:focus-visible .desc-icon {
  fill: var(--yp-text-inverse);
  opacity: 1;
}

/* 激活-升序（图4：上方亮白，下方半透明淡蓝） */
.sort-by-column--asc .asc-icon {
  fill: var(--yp-text-inverse) !important;
  opacity: 1 !important;
}

.sort-by-column--asc .desc-icon {
  fill: var(--yp-text-inverse) !important;
  opacity: 0.42 !important;
}

/* 激活-降序（图3：上方半透明淡蓝，下方亮白） */
.sort-by-column--desc .asc-icon {
  fill: var(--yp-text-inverse) !important;
  opacity: 0.42 !important;
}

.sort-by-column--desc .desc-icon {
  fill: var(--yp-text-inverse) !important;
  opacity: 1 !important;
}

/* 清除与保存气泡容器及滑出动画（图5） */
.clear-button-wrapper,
.save-button-wrapper {
  position: absolute;
  z-index: 1;
  top: 50%;
  display: flex;
  height: 24px;
  align-items: center;
  opacity: 0;
  pointer-events: none;
  transition: opacity 160ms cubic-bezier(0.2, 0, 0, 1), transform 160ms cubic-bezier(0.2, 0, 0, 1);
}

.clear-button-wrapper {
  right: 100%;
  margin-right: 6px;
  transform: translate(16px, -50%) scale(0.85);
}

.save-button-wrapper {
  left: 100%;
  margin-left: 6px;
  transform: translate(-16px, -50%) scale(0.85);
}

.monday-column-quick-sort:hover .clear-button-wrapper,
.monday-column-quick-sort:focus-within .clear-button-wrapper,
.sort-by-column:hover .clear-button-wrapper,
.sort-by-column:focus-within .clear-button-wrapper {
  opacity: 1;
  pointer-events: auto;
  transform: translate(0, -50%) scale(1);
}

.monday-column-quick-sort:hover .save-button-wrapper,
.monday-column-quick-sort:focus-within .save-button-wrapper,
.sort-by-column:hover .save-button-wrapper,
.sort-by-column:focus-within .save-button-wrapper {
  opacity: 1;
  pointer-events: auto;
  transform: translate(0, -50%) scale(1);
}

.clear-button,
.save-button {
  display: inline-flex;
  height: 24px;
  min-width: 48px;
  align-items: center;
  justify-content: center;
  padding: 0 10px;
  border: 1px solid var(--yp-border-subtle);
  border-radius: 12px;
  color: var(--yp-text-primary);
  background: var(--yp-bg-surface);
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  white-space: nowrap;
  cursor: pointer;
  box-shadow: 0 2px 8px color-mix(in srgb, var(--yp-text-primary) 10%, transparent);
  transition: color 120ms ease, background 120ms ease, border-color 120ms ease, box-shadow 120ms ease;
}

.clear-button:hover,
.clear-button:focus-visible,
.save-button:hover,
.save-button:focus-visible {
  color: var(--yp-action-primary);
  background: var(--yp-bg-hover);
  border-color: color-mix(in srgb, var(--yp-action-primary) 25%, transparent);
  box-shadow: 0 2px 10px color-mix(in srgb, var(--yp-text-primary) 14%, transparent);
  outline: none;
}

.clear-button:disabled,
.save-button:disabled,
.sort-trigger:disabled {
  cursor: wait;
  opacity: 0.7;
}

:global(.monday-sort-tooltip.el-popper) {
  max-width: 240px;
  padding: 7px 10px;
  font-size: 12px;
  line-height: 1.4;
}

@media (prefers-reduced-motion: reduce) {
  .sort-by-column,
  .clear-button-wrapper,
  .save-button-wrapper {
    transition: none;
  }
}
</style>
