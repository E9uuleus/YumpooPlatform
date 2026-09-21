<script setup lang="ts">
import type { DashboardChartMeasure } from '@yumpoo/api-client'
import { ElSelect as ElSelectRaw, ElOption as ElOptionRaw } from 'element-plus'
import type { DefineComponent } from 'vue'
import { metrics } from './dashboardModel'
import { calculations } from './chartModel'
const ElSelect = ElSelectRaw as unknown as DefineComponent
const ElOption = ElOptionRaw as unknown as DefineComponent
const props = defineProps<{ modelValue: DashboardChartMeasure; label: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: DashboardChartMeasure] }>()
function update(field: 'metric' | 'calculation', value: string) { emit('update:modelValue', { ...props.modelValue, [field]: value }) }
</script>
<template>
  <div class="chart-field">
    <span>{{ label }}</span><el-select
      :model-value="modelValue.metric"
      filterable
      popper-class="chart-settings-select-menu"
      :aria-label="label"
      @update:model-value="update('metric', $event)"
    >
      <el-option
        v-for="(title, value) in metrics"
        :key="value"
        :value="value"
        :label="title"
      />
    </el-select>
  </div>
  <div
    v-if="modelValue.metric === 'DURATION'"
    class="chart-field"
  >
    <span>汇总方式</span><el-select
      :model-value="modelValue.calculation"
      popper-class="chart-settings-select-menu"
      :aria-label="`${label}汇总方式`"
      @update:model-value="update('calculation', $event)"
    >
      <el-option
        v-for="(title, value) in calculations"
        :key="value"
        :value="value"
        :label="title"
      />
    </el-select>
  </div>
</template>
