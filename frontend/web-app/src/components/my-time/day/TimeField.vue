<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElInput } from 'element-plus'
import { atMinute, formatClock, parseClock, SNAP_MS, snap } from './dayModel'

const props = defineProps<{ modelValue: number; date: string; timezone: string; dayStart: number; dayEnd: number; label: string; immediate?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: number]; validity: [valid: boolean] }>()

const format = (ms: number) => formatClock(ms, props.timezone, props.dayEnd)
const text = ref(format(props.modelValue))
const invalid = ref(false)
watch(() => props.modelValue, value => {
  const minute = parseClock(text.value)
  if (!props.immediate || minute === null || atMinute(props.date, minute, props.timezone) !== value) text.value = format(value)
  invalid.value = false
})

function commit(normalize = true) {
  const minute = parseClock(text.value)
  emit('validity', minute !== null)
  if (minute === null) { invalid.value = true; return }
  invalid.value = false
  const value = atMinute(props.date, minute, props.timezone)
  if (normalize) text.value = format(value)
  if (value !== props.modelValue) emit('update:modelValue', value)
}

function nudge(event: Event, direction: 1 | -1) {
  event.preventDefault()
  emit('validity', true)
  invalid.value = false
  const next = snap(props.modelValue + direction * SNAP_MS, props.dayStart)
  const value = Math.min(Math.max(next, props.dayStart), props.dayEnd)
  text.value = format(value)
  emit('update:modelValue', value)
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
    @input="immediate && commit(false)"
    @blur="commit()"
    @keydown.enter.prevent="commit()"
    @keydown.up="nudge($event, 1)"
    @keydown.down="nudge($event, -1)"
  />
</template>

<style scoped>
.time-field{width:64px}
.time-field :deep(.el-input__inner){text-align:center;font-family:var(--yp-font-mono);font-variant-numeric:tabular-nums}
.time-field.is-invalid :deep(.el-input__wrapper){box-shadow:0 0 0 1px var(--yp-status-red) inset}
</style>
