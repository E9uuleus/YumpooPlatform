<script setup lang="ts">
import './workItemTableSettings.css'
import {
  WorkItemColoringColumn,
  WorkItemColoringTarget,
  type WorkItemColoringOperator,
  type WorkItemColoringRule,
} from '@yumpoo/api-client'
import { ElDatePicker, ElInput, ElOption as ElOptionRaw, ElPopover, ElSelect as ElSelectRaw } from 'element-plus'
import type { DefineComponent } from 'vue'
import { mondayWorkItemLabelColors } from './workItemLabelColors'
import {
  COLORING_COLUMNS,
  COLORING_OPERATOR_LABELS,
  MAX_COLORING_RULES,
  coloringBackground,
  coloringColumn,
  coloringValueKind,
  type TableSettingsCatalog,
} from './workItemTableSettings'

const props = defineProps<{ rules: WorkItemColoringRule[]; catalog: TableSettingsCatalog }>()
const emit = defineEmits<{ change: [rules: WorkItemColoringRule[]]; back: [] }>()
const ElOption = ElOptionRaw as unknown as DefineComponent
const ElSelect = ElSelectRaw as unknown as DefineComponent
const TARGETS = [
  { value: WorkItemColoringTarget.Cell, label: '单元格' },
  { value: WorkItemColoringTarget.Row, label: '整行' },
]

function replace(index: number, next: WorkItemColoringRule): void {
  emit('change', props.rules.map((rule, current) => current === index ? next : rule))
}

function add(): void {
  if (props.rules.length >= MAX_COLORING_RULES) return
  const color = mondayWorkItemLabelColors[props.rules.length % mondayWorkItemLabelColors.length]!
  emit('change', [...props.rules, {
    id: globalThis.crypto.randomUUID(), target: WorkItemColoringTarget.Row, colorToken: color.token,
    column: null, operator: null, values: [],
  }])
}

function remove(index: number): void {
  emit('change', props.rules.filter((_, current) => current !== index))
}

function setColumn(index: number, rule: WorkItemColoringRule, value: WorkItemColoringColumn): void {
  const column = coloringColumn(value)
  replace(index, {
    ...rule, column: value, operator: column?.operators[0] ?? null, values: [],
    target: column?.cell ? rule.target : WorkItemColoringTarget.Row,
  })
}

function setOperator(index: number, rule: WorkItemColoringRule, value: WorkItemColoringOperator): void {
  const kind = coloringValueKind(value)
  replace(index, { ...rule, operator: value, values: kind !== 'none' && kind === coloringValueKind(rule.operator) ? rule.values : [] })
}

function valueOptions(column: WorkItemColoringColumn | null): Array<{ value: string; label: string }> {
  const name = (label: string, active: boolean) => active ? label : `${label}（已停用）`
  switch (column) {
    case WorkItemColoringColumn.Assignee: return props.catalog.members.map(item => ({ value: item.userId, label: item.displayName }))
    case WorkItemColoringColumn.Status: return props.catalog.statuses.map(item => ({ value: item.code, label: name(item.displayName, item.active) }))
    case WorkItemColoringColumn.Priority: return props.catalog.priorities.map(item => ({ value: item.code, label: name(item.displayName, item.active) }))
    case WorkItemColoringColumn.Content: return props.catalog.contents.map(item => ({ value: item.id, label: name(item.name, item.active) }))
    default: return []
  }
}

/** 已离开的成员或已删除的标签不以原始 ID 显示。 */
function listValues(rule: WorkItemColoringRule): string[] {
  const known = new Set(valueOptions(rule.column).map(option => option.value))
  return rule.values.filter(value => known.has(value))
}
</script>

<template>
  <section class="table-settings-panel" aria-label="条件着色">
    <header class="table-settings-panel__header">
      <button type="button" class="table-settings-back" aria-label="返回表格设置" @click="emit('back')">‹</button>
      <strong>条件着色</strong>
    </header>
    <p class="table-settings-panel__hint">满足条件的单元格或整行显示所选背景色；多条同时满足时，列表中靠前的条件优先。</p>
    <p v-if="!rules.length" class="table-settings-empty">还没有条件</p>
    <div v-else class="table-settings-rules">
      <div v-for="(rule, index) in rules" :key="rule.id" class="table-settings-rule">
        <el-popover trigger="click" placement="bottom-start" :width="248" popper-class="work-items-popover work-item-view-control">
          <template #reference>
            <button
              type="button"
              class="table-settings-swatch"
              :style="{ background: coloringBackground(rule.colorToken) }"
              :aria-label="`第 ${index + 1} 条条件的背景色`"
            />
          </template>
          <div class="table-settings-palette" role="listbox" aria-label="背景色">
            <button
              v-for="color in mondayWorkItemLabelColors"
              :key="color.token"
              type="button"
              role="option"
              class="table-settings-palette__color"
              :class="{ 'table-settings-palette__color--current': rule.colorToken === color.token }"
              :aria-selected="rule.colorToken === color.token"
              :title="color.label"
              :style="{ background: coloringBackground(color.token) }"
              @click="replace(index, { ...rule, colorToken: color.token })"
            />
          </div>
        </el-popover>
        <el-select
          :model-value="rule.target"
          aria-label="作用范围"
          popper-class="work-item-view-control"
          @update:model-value="replace(index, { ...rule, target: $event })"
        >
          <el-option
            v-for="target in TARGETS"
            :key="target.value"
            :value="target.value"
            :label="target.label"
            :disabled="target.value === WorkItemColoringTarget.Cell && coloringColumn(rule.column)?.cell === false"
          />
        </el-select>
        <span class="table-settings-rule__when">当</span>
        <el-select
          :model-value="rule.column ?? undefined"
          placeholder="选择列"
          aria-label="条件列"
          popper-class="work-item-view-control"
          @update:model-value="setColumn(index, rule, $event)"
        >
          <el-option v-for="column in COLORING_COLUMNS" :key="column.value" :value="column.value" :label="column.label" />
        </el-select>
        <el-select
          :model-value="rule.operator ?? undefined"
          :disabled="!rule.column"
          placeholder="条件"
          aria-label="条件运算"
          popper-class="work-item-view-control"
          @update:model-value="setOperator(index, rule, $event)"
        >
          <el-option
            v-for="operator in coloringColumn(rule.column)?.operators ?? []"
            :key="operator"
            :value="operator"
            :label="COLORING_OPERATOR_LABELS[operator]"
          />
        </el-select>
        <el-select
          v-if="coloringValueKind(rule.operator) === 'list'"
          :model-value="listValues(rule)"
          multiple
          collapse-tags
          collapse-tags-tooltip
          filterable
          :multiple-limit="20"
          placeholder="选择值"
          aria-label="条件值"
          popper-class="work-item-view-control"
          @update:model-value="replace(index, { ...rule, values: $event })"
        >
          <el-option v-for="option in valueOptions(rule.column)" :key="option.value" :value="option.value" :label="option.label" />
        </el-select>
        <el-input
          v-else-if="coloringValueKind(rule.operator) === 'text'"
          :model-value="rule.values[0] ?? ''"
          maxlength="100"
          placeholder="输入文本"
          aria-label="条件文本"
          @update:model-value="replace(index, { ...rule, values: $event.trim() ? [$event] : [] })"
        />
        <el-date-picker
          v-else-if="coloringValueKind(rule.operator) === 'date'"
          :model-value="rule.values[0] ?? ''"
          type="date"
          value-format="YYYY-MM-DD"
          :clearable="false"
          placeholder="选择日期"
          aria-label="条件日期"
          popper-class="work-item-view-control"
          @update:model-value="replace(index, { ...rule, values: $event ? [String($event)] : [] })"
        />
        <span v-else aria-hidden="true" />
        <button type="button" class="table-settings-icon-button" :aria-label="`删除第 ${index + 1} 条条件`" @click="remove(index)">×</button>
      </div>
    </div>
    <div>
      <button type="button" class="table-settings-link" :disabled="rules.length >= MAX_COLORING_RULES" @click="add">+ 新增条件</button>
    </div>
  </section>
</template>
