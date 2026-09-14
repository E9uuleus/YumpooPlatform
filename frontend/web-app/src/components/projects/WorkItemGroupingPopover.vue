<script setup lang="ts">
import { ElCheckbox, ElOption as ElOptionRaw, ElPopover, ElSelect as ElSelectRaw } from 'element-plus'
import { ref, type DefineComponent } from 'vue'
import { groupingFields, groupOrders, type GroupField, type GroupOrder } from './workItemGrouping'
defineProps<{ field: GroupField | ''; order: GroupOrder; showEmpty: boolean; disabled: boolean }>()
const emit = defineEmits<{ field: [value: GroupField | '']; order: [value: GroupOrder]; showEmpty: [value: boolean] }>()
const open = ref(false)
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
</script>

<template>
  <el-popover
    v-model:visible="open"
    placement="bottom-start"
    :width="420"
    trigger="click"
    :disabled="disabled"
    popper-class="work-items-popover work-item-view-control"
  >
    <template #reference>
      <button
        class="toolbar-button grouping-toolbar-button"
        :class="{ active: field }"
        :disabled="disabled"
        :aria-expanded="open"
        aria-label="分组"
        type="button"
      >
        <svg
          width="16"
          height="16"
          viewBox="0 0 20 20"
          fill="none"
          aria-hidden="true"
        >
          <rect
            x="2.5"
            y="3"
            width="15"
            height="14"
            rx="1.5"
            stroke="currentColor"
            stroke-width="1.4"
          />
          <path
            d="M7 3v14M7 8h10.5M7 12h10.5"
            stroke="currentColor"
            stroke-width="1.4"
          />
        </svg>
        <span>分组<span v-if="field"> / 1</span></span>
      </button>
    </template>
    <section
      class="grouping-popover"
      aria-label="工作项分组设置"
    >
      <header>
        <strong>按字段分组</strong><button
          type="button"
          class="grouping-clear"
          :disabled="!field"
          @click="emit('field', '')"
        >
          清除
        </button>
      </header>
      <div class="grouping-selects">
        <el-select
          :model-value="field || undefined"
          aria-label="分组字段"
          popper-class="work-item-grouping-options"
          placeholder="选择字段"
          @update:model-value="emit('field', $event as GroupField)"
        >
          <el-option
            v-for="option in groupingFields"
            :key="option.value"
            :value="option.value"
            :label="option.label"
          />
        </el-select>
        <el-select
          :model-value="field ? order : undefined"
          :disabled="!field"
          aria-label="分组顺序"
          popper-class="work-item-grouping-options"
          placeholder="选择规则"
          @update:model-value="emit('order', $event as GroupOrder)"
        >
          <el-option
            v-for="option in groupOrders(field)"
            :key="option.value"
            :value="option.value"
            :label="option.label"
          />
        </el-select>
      </div>
      <el-checkbox
        :model-value="showEmpty"
        :disabled="!field"
        @update:model-value="emit('showEmpty', Boolean($event))"
      >
        显示空组
      </el-checkbox>
    </section>
  </el-popover>
</template>

<style scoped>
.grouping-popover { display: grid; gap: 16px; padding: 4px; }
header { display: flex; align-items: center; justify-content: space-between; }
strong { font-size: 14px; font-weight: 600; }
.grouping-selects { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.grouping-clear { border: 0; background: transparent; color: var(--yp-text-secondary); padding: 4px 8px; cursor: pointer; border-radius: var(--yp-radius-sm); }
.grouping-clear:hover:not(:disabled) { background: var(--yp-bg-hover); }
.grouping-clear:disabled { opacity: .4; cursor: default; }
</style>
