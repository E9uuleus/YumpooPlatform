<script setup lang="ts">
/** 首次读取时的占位：与目标区域形状一致的灰块，整块共用一道从左到右的扫光；减少动态效果时只留灰块。 */
withDefaults(defineProps<{
  variant: 'table' | 'list' | 'cards' | 'form' | 'chart'
  /** table / list 的行数，cards 的卡片数，form 的字段数。 */
  rows?: number
  /** 仅 table：列数。 */
  columns?: number
  label?: string
}>(), { rows: 6, columns: 4, label: '正在加载…' })

const WIDTHS = [72, 54, 86, 64, 48, 78]
const BARS = [46, 68, 38, 82, 56, 74, 42, 60]
</script>

<template>
  <div
    class="yp-skeleton"
    :class="`yp-skeleton--${variant}`"
    role="status"
    :aria-label="label"
  >
    <template v-if="variant === 'table'">
      <div
        class="yp-skeleton__row yp-skeleton__row--head"
        :style="{ '--columns': columns }"
      >
        <span
          v-for="column in columns"
          :key="column"
          class="yp-skeleton__block yp-skeleton__block--short"
        />
      </div>
      <div
        v-for="row in rows"
        :key="row"
        class="yp-skeleton__row"
        :style="{ '--columns': columns }"
      >
        <span
          v-for="column in columns"
          :key="column"
          class="yp-skeleton__block"
          :style="{ width: `${WIDTHS[(row + column) % WIDTHS.length]}%` }"
        />
      </div>
    </template>
    <template v-else-if="variant === 'list'">
      <div
        v-for="row in rows"
        :key="row"
        class="yp-skeleton__item"
      >
        <span class="yp-skeleton__avatar" />
        <span class="yp-skeleton__lines">
          <span
            class="yp-skeleton__block"
            :style="{ width: `${WIDTHS[row % WIDTHS.length]}%` }"
          />
          <span class="yp-skeleton__block yp-skeleton__block--short" />
        </span>
      </div>
    </template>
    <template v-else-if="variant === 'cards'">
      <div
        v-for="card in rows"
        :key="card"
        class="yp-skeleton__card"
      >
        <span class="yp-skeleton__block yp-skeleton__block--short" />
        <span class="yp-skeleton__block yp-skeleton__block--title" />
        <span class="yp-skeleton__block" />
      </div>
    </template>
    <template v-else-if="variant === 'form'">
      <div
        v-for="field in rows"
        :key="field"
        class="yp-skeleton__field"
      >
        <span class="yp-skeleton__block yp-skeleton__block--short" />
        <span class="yp-skeleton__control" />
      </div>
    </template>
    <div
      v-else
      class="yp-skeleton__chart"
    >
      <span
        v-for="(height, index) in BARS"
        :key="index"
        class="yp-skeleton__bar"
        :style="{ height: `${height}%` }"
      />
    </div>
  </div>
</template>

<style scoped>
.yp-skeleton {
  position: relative;
  display: grid;
  gap: var(--yp-space-3);
  /* clip 不是滚动容器，放进 sticky 布局时不改变滚动参照。 */
  overflow: clip;
}

.yp-skeleton::after {
  position: absolute;
  z-index: 1;
  inset: 0;
  background: linear-gradient(100deg, transparent 30%, var(--yp-skeleton-shine) 50%, transparent 70%);
  content: '';
  pointer-events: none;
  transform: translateX(-100%);
  animation: yp-skeleton-sweep 1.6s ease-in-out infinite;
}

.yp-skeleton__block {
  display: block;
  height: 10px;
  border-radius: var(--yp-radius-pill);
  background: var(--yp-skeleton-block);
}

.yp-skeleton__block--short {
  width: 30%;
}

.yp-skeleton__block--title {
  width: 60%;
  height: 14px;
  background: var(--yp-skeleton-block-strong);
}

.yp-skeleton--table {
  gap: 0;
}

.yp-skeleton__row {
  display: grid;
  grid-template-columns: repeat(var(--columns), minmax(0, 1fr));
  align-items: center;
  gap: var(--yp-space-4);
  height: var(--yp-table-row-height);
  padding: 0 var(--yp-space-3);
  border-bottom: 1px solid var(--yp-table-divider);
}

.yp-skeleton__row--head {
  background: var(--yp-table-header-bg);
}

.yp-skeleton__item {
  display: flex;
  min-height: 48px;
  align-items: center;
  gap: var(--yp-space-3);
}

.yp-skeleton__avatar {
  width: 28px;
  height: 28px;
  flex: none;
  border-radius: 50%;
  background: var(--yp-skeleton-block-strong);
}

.yp-skeleton__lines {
  display: grid;
  flex: 1;
  gap: var(--yp-space-2);
}

.yp-skeleton--cards {
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--yp-space-4);
}

.yp-skeleton__card {
  display: grid;
  min-height: 112px;
  align-content: start;
  gap: var(--yp-space-3);
  padding: var(--yp-space-4);
  border-radius: var(--yp-radius-md);
  background: var(--yp-bg-sunken);
}

.yp-skeleton--form {
  max-width: 640px;
  gap: var(--yp-space-5);
}

.yp-skeleton__field {
  display: grid;
  gap: var(--yp-space-2);
}

.yp-skeleton__control {
  height: var(--yp-control-height);
  border-radius: var(--yp-input-radius);
  background: var(--yp-skeleton-block);
}

.yp-skeleton__chart {
  display: flex;
  height: 220px;
  align-items: flex-end;
  gap: var(--yp-space-3);
  padding: var(--yp-space-4) 0;
}

.yp-skeleton__bar {
  flex: 1;
  border-radius: var(--yp-radius-sm) var(--yp-radius-sm) 0 0;
  background: var(--yp-skeleton-block);
}

@keyframes yp-skeleton-sweep {
  to { transform: translateX(100%); }
}

@media (prefers-reduced-motion: reduce) {
  .yp-skeleton::after { display: none; }
}
</style>
