<script setup lang="ts">
import { computed } from 'vue'
import { TimeSessionFillMode } from '@yumpoo/api-client'
import YpSegmented from '../../yp/YpSegmented.vue'
import { durationLabel, type OverlapEffect, type Range } from './dayModel'

const props = defineProps<{ modelValue: TimeSessionFillMode; effect: OverlapEffect; range: Range; itemCount: number }>()
const emit = defineEmits<{ 'update:modelValue': [mode: TimeSessionFillMode] }>()

const names = (titles: string[]) => titles.length > 2 ? `${titles.slice(0, 2).join('、')} 等 ${titles.length} 项` : titles.join('、')
const options = computed(() => [
  { value: TimeSessionFillMode.Gaps, label: '只填空白', disabled: props.effect.fullyCovered,
    title: props.effect.fullyCovered ? '该时段已全部有记录，没有空白可填' : '只补没有任何记录的部分' },
  { value: TimeSessionFillMode.Parallel, label: '并行均摊', title: '与已有记录同时进行，重叠部分按项数平分' },
  { value: TimeSessionFillMode.Replace, label: '覆盖', disabled: !props.effect.trimmed.length,
    title: props.effect.trimmed.length ? '裁剪或删除重叠的其他记录' : '重叠的记录都不可修改' },
])
const effectText = computed(() => {
  const { effect, range } = props
  if (props.modelValue === TimeSessionFillMode.Gaps)
    return `补录 ${durationLabel(effect.freeMs)}，跳过已有记录的 ${durationLabel(range.end - range.start - effect.freeMs)}`
  if (props.modelValue === TimeSessionFillMode.Parallel)
    return `与 ${names(effect.parallelItems)} 并行，重叠部分按 ${effect.parallelItems.length + props.itemCount} 项平分计入`
  const kept = effect.kept.length ? `；${names(effect.kept.map(block => block.title))} 不可修改，将保持并行` : ''
  return `裁剪或删除 ${names(effect.trimmed.map(block => block.title))} 在此时段的记录${kept}`
})
</script>

<template>
  <div class="overlap-mode">
    <p class="overlap-mode__lead">
      与 {{ effect.overlapping.length }} 条已有记录重叠
    </p>
    <YpSegmented
      :model-value="modelValue"
      :options="options"
      label="与已有记录重叠时"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <p
      class="overlap-mode__effect"
      aria-live="polite"
    >
      {{ effectText }}
    </p>
  </div>
</template>

<style scoped>
.overlap-mode{display:grid;gap:6px;padding:10px;border-radius:var(--yp-radius-sm);background:var(--yp-bg-sunken)}
.overlap-mode__lead{margin:0;color:var(--yp-text-secondary);font-size:12px}
.overlap-mode__effect{margin:0;color:var(--yp-text-muted);font-size:12px;line-height:18px}
</style>
