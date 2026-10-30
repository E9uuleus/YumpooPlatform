<script setup lang="ts">
import { computed } from 'vue'
import './workItemSkeleton.css'

export interface WorkItemSkeletonColumn {
  key: string
  width: number
  collapsed?: boolean
}

const props = withDefaults(defineProps<{
  /** 第一列是固定的名称列，其余按真实列宽排列。 */
  columns: ReadonlyArray<WorkItemSkeletonColumn>
  /** 名称列之前的固定列宽度；最后一列画勾选框。 */
  leadWidths: ReadonlyArray<number>
  /** 骨架不从表格最左侧开始时（分组加载行），固定列要再让出前面已固定的宽度。 */
  stickyOffset?: number
  trailingWidth?: number
  rows: number
  rowHeight?: number
  header?: boolean
  fade?: boolean
  label?: string
}>(), {
  stickyOffset: 0,
  trailingWidth: 0,
  rowHeight: 36,
  header: false,
  fade: false,
  label: '正在加载工作项',
})

const TITLE_WIDTHS = ['62%', '48%', '70%', '55%', '40%', '66%']

const leadLefts = computed(() => props.leadWidths.reduce<number[]>((offsets, width, index) => [...offsets, (offsets[index] ?? 0) + width], [props.stickyOffset]))
const gridStyle = computed(() => ({
  '--work-item-skeleton-columns': [...props.leadWidths, ...props.columns.map(column => column.width), ...(props.trailingWidth ? [props.trailingWidth] : [])]
    .map(width => `${width}px`).join(' '),
  '--work-item-skeleton-row-height': `${props.rowHeight}px`,
  '--work-item-skeleton-title-left': `${leadLefts.value[props.leadWidths.length] ?? 0}px`,
}))

function shape(column: WorkItemSkeletonColumn): string {
  if (column.collapsed) return 'empty'
  if (column.key === 'status' || column.key === 'priority') return 'block'
  if (column.key === 'assignee') return 'avatar'
  if (column.key === 'content') return 'pill'
  if (column.key === 'dueDate' || column.key === 'updatedAt' || column.key === 'timeTracking') return 'short'
  return 'medium'
}
</script>

<template>
  <div
    class="work-item-skeleton work-item-table-skeleton"
    :class="{ 'work-item-table-skeleton--fade': fade }"
    role="status"
    :aria-label="label"
    :style="gridStyle"
  >
    <div
      v-if="header"
      class="work-item-table-skeleton__row work-item-table-skeleton__row--header"
      aria-hidden="true"
    >
      <span
        v-for="(_, index) in leadWidths"
        :key="`lead-${index}`"
        class="work-item-table-skeleton__lead"
        :class="{ 'work-item-table-skeleton__lead--select': index === leadWidths.length - 1 }"
        :style="{ left: `${leadLefts[index]}px` }"
      />
      <span
        v-for="(column, index) in columns"
        :key="column.key"
        class="work-item-table-skeleton__cell"
        :class="{ 'work-item-table-skeleton__cell--title': index === 0 }"
      ><i
        v-if="!column.collapsed"
        class="work-item-skeleton-block work-item-table-skeleton__label"
      /></span>
      <span
        v-if="trailingWidth"
        class="work-item-table-skeleton__trail"
      />
    </div>
    <div
      v-for="row in rows"
      :key="row"
      class="work-item-table-skeleton__row"
      :style="{ animationDelay: `${(row - 1) * 40}ms` }"
      aria-hidden="true"
    >
      <span
        v-for="(_, index) in leadWidths"
        :key="`lead-${index}`"
        class="work-item-table-skeleton__lead"
        :class="{ 'work-item-table-skeleton__lead--select': index === leadWidths.length - 1 }"
        :style="{ left: `${leadLefts[index]}px` }"
      ><i
        v-if="index === leadWidths.length - 1"
        class="work-item-table-skeleton__checkbox"
      /></span>
      <span
        v-for="(column, index) in columns"
        :key="column.key"
        class="work-item-table-skeleton__cell"
        :class="[`work-item-table-skeleton__cell--${index === 0 ? 'title' : shape(column)}`]"
      ><i
        v-if="index === 0 || shape(column) !== 'empty'"
        class="work-item-skeleton-block"
        :style="index === 0 ? { width: TITLE_WIDTHS[(row - 1) % TITLE_WIDTHS.length] } : undefined"
      /></span>
      <span
        v-if="trailingWidth"
        class="work-item-table-skeleton__trail"
      />
    </div>
  </div>
</template>

<style scoped>
.work-item-table-skeleton {
  min-width: max-content;
}

.work-item-table-skeleton--fade {
  mask-image: linear-gradient(to bottom, black 45%, transparent);
}

.work-item-table-skeleton__row {
  display: grid;
  grid-template-columns: var(--work-item-skeleton-columns);
  height: var(--work-item-skeleton-row-height);
  animation: work-item-skeleton-row-in 240ms var(--yp-ease-standard) backwards;
}

.work-item-table-skeleton__row--header {
  height: var(--work-item-table-header-height, 38px);
}

.work-item-table-skeleton__lead,
.work-item-table-skeleton__cell--title {
  position: sticky;
  z-index: 2;
  background: var(--yp-bg-surface);
}

.work-item-table-skeleton__cell--title {
  left: var(--work-item-skeleton-title-left);
}

.work-item-table-skeleton__lead--select,
.work-item-table-skeleton__cell,
.work-item-table-skeleton__trail {
  box-sizing: border-box;
  border-bottom: 1px solid var(--work-item-skeleton-line);
}

.work-item-table-skeleton__lead--select,
.work-item-table-skeleton__cell {
  display: flex;
  align-items: center;
  border-right: 1px solid var(--work-item-skeleton-line);
}

.work-item-table-skeleton__lead--select {
  justify-content: center;
}

.work-item-table-skeleton__row:not(.work-item-table-skeleton__row--header) .work-item-table-skeleton__lead--select::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 6px;
  background: var(--work-item-skeleton-accent, var(--work-item-skeleton-block-strong));
  content: '';
}

.work-item-table-skeleton__checkbox {
  width: 16px;
  height: 16px;
  box-sizing: border-box;
  border: 1px solid var(--work-item-skeleton-block-strong);
  border-radius: 2px;
}

.work-item-table-skeleton__cell {
  justify-content: center;
  padding: 0 var(--yp-space-3);
}

.work-item-table-skeleton__cell .work-item-skeleton-block { width: 56%; }

.work-item-table-skeleton__cell--title {
  justify-content: flex-start;
  padding-left: 32px;
}

.work-item-table-skeleton__cell--title .work-item-skeleton-block { height: 12px; }
.work-item-table-skeleton__cell--medium .work-item-skeleton-block { width: 44%; }

.work-item-table-skeleton__cell--block { padding: 0; }
.work-item-table-skeleton__cell--block .work-item-skeleton-block {
  width: 100%;
  height: 100%;
  border-radius: 0;
  background: var(--work-item-skeleton-block-strong);
}

.work-item-table-skeleton__cell--avatar .work-item-skeleton-block {
  width: 24px;
  height: 24px;
  border-radius: 50%;
}

.work-item-table-skeleton__cell--pill .work-item-skeleton-block {
  width: 100%;
  height: 22px;
}

.work-item-table-skeleton__cell .work-item-table-skeleton__label {
  width: 48%;
  height: 8px;
}

@keyframes work-item-skeleton-row-in {
  from { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .work-item-table-skeleton__row { animation: none; }
}
</style>
