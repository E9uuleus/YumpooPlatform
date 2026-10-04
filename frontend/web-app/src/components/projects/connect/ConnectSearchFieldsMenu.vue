<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElCheckbox, ElIcon, ElInput } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import type { ConnectCandidateField } from '@yumpoo/api-client'
import { connectSearchFields } from './connectSearchFields'

const props = defineProps<{ modelValue: ConnectCandidateField[] }>()
const emit = defineEmits<{ 'update:modelValue': [fields: ConnectCandidateField[]] }>()
const filter = ref('')
const visible = computed(() => connectSearchFields.filter(field => field.label.includes(filter.value.trim())))
const all = computed(() => props.modelValue.length === connectSearchFields.length)
const selected = (field: ConnectCandidateField) => props.modelValue.includes(field)
function toggle(field: ConnectCandidateField) {
  if (selected(field)) {
    // Searching needs at least one field; the last one stays on.
    if (props.modelValue.length > 1) emit('update:modelValue', props.modelValue.filter(value => value !== field))
  } else emit('update:modelValue', connectSearchFields.map(item => item.value).filter(value => value === field || selected(value)))
}
function toggleAll() { emit('update:modelValue', all.value ? [connectSearchFields[0]!.value] : connectSearchFields.map(field => field.value)) }
</script>

<template>
  <div
    class="connect-fields"
    role="group"
    aria-label="选择搜索字段"
  >
    <h4>选择搜索字段</h4>
    <el-input
      v-model="filter"
      :prefix-icon="Search"
      size="small"
      placeholder="查找字段"
      aria-label="查找字段"
    />
    <div
      class="connect-fields__option connect-fields__option--all"
      @click="toggleAll"
    >
      <el-checkbox
        :model-value="all"
        :indeterminate="!all && modelValue.length > 0"
        aria-label="全部字段"
        @click.stop
        @change="toggleAll"
      />
      <strong>全部字段</strong><small>已选 {{ modelValue.length }}</small>
    </div>
    <div
      v-for="field in visible"
      :key="field.value"
      class="connect-fields__option"
      @click="toggle(field.value)"
    >
      <el-checkbox
        :model-value="selected(field.value)"
        :aria-label="field.label"
        @click.stop
        @change="toggle(field.value)"
      />
      <span
        class="connect-fields__icon"
        :style="{ background: field.tone }"
      ><el-icon><component :is="field.icon" /></el-icon></span>
      <span>{{ field.label }}</span>
    </div>
  </div>
</template>

<style scoped>
.connect-fields { display: grid; gap: 4px; color: var(--yp-text-primary); }
.connect-fields h4 { margin: 0 0 6px; font-family: var(--yp-font-heading); font-size: 14px; font-weight: 600; }
.connect-fields .el-input { margin-bottom: 4px; }
.connect-fields__option { display: flex; align-items: center; gap: 8px; min-height: 32px; padding: 0 6px; border-radius: var(--yp-radius-sm); font-size: 13px; cursor: pointer; }
.connect-fields__option:hover { background: var(--yp-bg-hover); }
.connect-fields__option--all small { margin-left: auto; color: var(--yp-text-muted); font-size: 12px; }
.connect-fields__icon { display: inline-grid; width: 20px; height: 20px; flex-shrink: 0; place-items: center; border-radius: var(--yp-radius-xs); color: var(--yp-text-inverse); font-size: 12px; }
</style>
