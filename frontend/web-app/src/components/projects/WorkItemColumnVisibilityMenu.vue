<script setup lang="ts" generic="T extends string">
import { ElCheckbox } from 'element-plus'
import { onBeforeUnmount, ref, watch } from 'vue'
import WorkItemFieldGlyph from './WorkItemFieldGlyph.vue'
import './workItemViewOptions.css'

const props = defineProps<{ columns: { key: T; label: string }[]; hidden: Set<T>; disabled?: boolean }>()
const emit = defineEmits<{ toggle: [key: T, visible: boolean] }>()
const hidden = ref<Set<string>>(new Set(props.hidden))
const pending = new Map<T, { visible: boolean; frame?: number; timer?: ReturnType<typeof setTimeout> }>()
watch(() => props.hidden, value => {
  const next = new Set<string>(value)
  pending.forEach((change, key) => { if (change.visible) next.delete(key); else next.add(key) })
  hidden.value = next
})

function toggle(key: T, visible: boolean): void {
  if (key === 'title' || props.disabled) return
  if (visible) hidden.value.delete(key); else hidden.value.add(key)
  const previous = pending.get(key)
  if (previous?.frame !== undefined) cancelAnimationFrame(previous.frame)
  clearTimeout(previous?.timer)
  const change: { visible: boolean; frame?: number; timer?: ReturnType<typeof setTimeout> } = { visible }
  pending.set(key, change)
  change.frame = requestAnimationFrame(() => {
    delete change.frame
    change.timer = setTimeout(() => { pending.delete(key); emit('toggle', key, visible) }, 0)
  })
}
onBeforeUnmount(() => pending.forEach(change => {
  if (change.frame !== undefined) cancelAnimationFrame(change.frame)
  clearTimeout(change.timer)
}))
</script>

<template>
  <div class="column-visibility-menu work-item-view-options-scroll">
    <strong>显示列</strong>
    <el-checkbox
      v-for="column in columns"
      :key="column.key"
      class="work-item-view-option"
      :model-value="!hidden.has(column.key)"
      :disabled="disabled || column.key === 'title'"
      @change="toggle(column.key, Boolean($event))"
    >
      <work-item-field-glyph :field="column.key" />{{ column.label }}
    </el-checkbox>
  </div>
</template>

<style scoped>
.column-visibility-menu { display: grid; gap: 4px; }
.column-visibility-menu > strong { margin-bottom: 4px; }
</style>
