<script setup lang="ts">
import { ElButton, ElPopover } from 'element-plus'
import { computed } from 'vue'
import { pageColumns, type WorkItemPagePresentation } from '../projects/workItemPagePresentation'
const props = defineProps<{ presentation: WorkItemPagePresentation }>()
const visible = computed(() => props.presentation.order.value.filter(key => !props.presentation.hidden.value.has(key)))
const subitemVisible = computed(() => props.presentation.subitemOrder.value.filter(key => !props.presentation.hidden.value.has(key)))
const label = (key: string) => pageColumns.find(c => c.key === key)!.label
</script>

<template>
  <el-popover
    trigger="click"
    placement="bottom-end"
    :width="260"
  >
    <template #reference>
      <el-button
        text
        aria-label="我的工作列设置"
        data-tour="my-work-columns"
      >
        列设置
      </el-button>
    </template>
    <div class="my-work-columns">
      <strong>显示列</strong>
      <label
        v-for="column in pageColumns"
        :key="column.key"
      >
        <input
          type="checkbox"
          :checked="!presentation.hidden.value.has(column.key)"
          :disabled="column.key === 'title'"
          @change="presentation.toggleColumn(column.key)"
        >
        {{ column.label }}
      </label>
      <label>主表冻结至
        <select
          aria-label="主表冻结至"
          :value="Math.min(presentation.pinned.value, visible.length)"
          @change="presentation.pinColumns(Number(($event.target as HTMLSelectElement).value))"
        >
          <option :value="0">工作项名称</option>
          <option
            v-for="(key, index) in visible"
            :key="key"
            :value="index + 1"
            :disabled="index + 1 > presentation.pinned.value && !presentation.canPin(index + 1)"
          >{{ label(key) }}</option>
        </select>
      </label>
      <label>子项冻结至
        <select
          aria-label="子项冻结至"
          :value="Math.min(presentation.subitemPinned.value, subitemVisible.length)"
          @change="presentation.pinColumns(Number(($event.target as HTMLSelectElement).value), true)"
        >
          <option :value="0">工作项名称</option>
          <option
            v-for="(key, index) in subitemVisible"
            :key="key"
            :value="index + 1"
            :disabled="index + 1 > presentation.subitemPinned.value && !presentation.canPin(index + 1, true)"
          >{{ label(key) }}</option>
        </select>
      </label>
    </div>
  </el-popover>
</template>

<style scoped>
.my-work-columns { display: grid; gap: 10px; color: var(--yp-text-primary); }
.my-work-columns label { display: flex; align-items: center; gap: 8px; }
.my-work-columns select { min-width: 0; flex: 1; padding: 4px; color: inherit; background: var(--yp-bg-surface); border: 1px solid var(--yp-border-default); border-radius: var(--yp-radius-sm); }
</style>
