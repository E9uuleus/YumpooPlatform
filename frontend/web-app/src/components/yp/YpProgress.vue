<script setup lang="ts">
import { ElProgress } from 'element-plus'
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  value?: number
  max?: number
  percent?: number | null | undefined
  state?: 'default' | 'complete' | 'blocked'
}>(), {
  value: 0,
  max: 0,
  percent: null,
  state: 'default',
})

const determinate = computed(() => props.percent !== null || props.max > 0)
const percentage = computed(() => {
  const raw = props.percent ?? (props.max > 0 ? props.value / props.max * 100 : 0)
  return Math.min(100, Math.max(0, Math.round(raw)))
})
const progressStatus = computed(() => {
  if (props.state === 'blocked') return 'exception'
  if (props.state === 'complete' || percentage.value === 100) return 'success'
  return ''
})
const label = computed(() => {
  if (!determinate.value) return '进度未知'
  if (props.percent === null && props.max > 0) return `${props.value}/${props.max} · ${percentage.value}%`
  return `${percentage.value}%`
})
</script>

<template>
  <div class="yp-progress">
    <el-progress
      :percentage="percentage"
      :status="progressStatus"
      :show-text="false"
      :stroke-width="8"
      :indeterminate="!determinate"
      :duration="1.6"
    />
    <span>{{ label }}</span>
  </div>
</template>

<style scoped>
.yp-progress {
  display: grid;
  grid-template-columns: minmax(80px, 1fr) auto;
  align-items: center;
  gap: var(--yp-space-2);
  min-width: 140px;
  font-variant-numeric: tabular-nums;
}

.yp-progress span {
  color: var(--yp-text-secondary);
  font-size: var(--yp-type-caption-size);
  white-space: nowrap;
}

.yp-progress :deep(.el-progress-bar__inner) {
  transition-duration: var(--yp-motion-progress);
}
</style>
