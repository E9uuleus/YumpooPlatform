<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElInput } from 'element-plus'
import { atMinute, formatClock, parseClock, SNAP_MS, snap } from './dayModel'

const props = defineProps<{ modelValue: number; date: string; timezone: string; dayStart: number; dayEnd: number; label: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: number] }>()

const format = (ms: number) => formatClock(ms, props.timezone, props.dayEnd)
const text = ref(format(props.modelValue))
const invalid = ref(false)
watch(() => props.modelValue, value => { text.value = format(value); invalid.value = false })

function commit() {
  const minute = parseClock(text.value)
  if (minute === null) { invalid.value = true; return }
  invalid.value = false
  const value = atMinute(props.date, minute, props.timezone)
  if (value === props.modelValue) text.value = format(value)
  else emit('update:modelValue', value)
}

function nudge(event: Event, direction: 1 | -1) {
  event.preventDefault()
  const next = snap(props.modelValue + direction * SNAP_MS, props.dayStart)
  emit('update:modelValue', Math.min(Math.max(next, props.dayStart), props.dayEnd))
}
</script>

<template>
  <el-input
    v-model="text"
    class="time-field"
    :class="{ 'is-invalid': invalid }"
    size="small"
    inputmode="numeric"
    maxlength="5"
    :aria-label="label"
    :aria-invalid="invalid"
    @blur="commit"
    @keydown.enter.prevent="commit"
    @keydown.up="nudge($event, 1)"
    @keydown.down="nudge($event, -1)"
  />
</template>

<style scoped>
.time-field{width:64px}
.time-field :deep(.el-input__inner){text-align:center;font-family:var(--yp-font-mono);font-variant-numeric:tabular-nums}
.time-field.is-invalid :deep(.el-input__wrapper){box-shadow:0 0 0 1px var(--yp-status-red) inset}
</style>
