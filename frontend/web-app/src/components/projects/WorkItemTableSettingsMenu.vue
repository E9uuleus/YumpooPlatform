<script setup lang="ts">
import './workItemTableSettings.css'
import { WorkItemTableHeight, type WorkItemTableSettingsUpdateRequest } from '@yumpoo/api-client'
import { ElCheckbox, ElPopover, ElTooltip } from 'element-plus'
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import WorkItemColoringRulesPanel from './WorkItemColoringRulesPanel.vue'
import WorkItemDefaultValuesPanel from './WorkItemDefaultValuesPanel.vue'
import WorkItemFieldGlyph from './WorkItemFieldGlyph.vue'
import type { TableSettingsSaveState } from './useWorkItemTableSettings'
import {
  HEIGHT_OPTIONS,
  heightOption,
  isColoringRuleComplete,
  type DefaultValueColumnKey,
  type TableSettingsCatalog,
} from './workItemTableSettings'

type Panel = 'menu' | 'pin' | 'coloring' | 'defaults'
type HeightField = 'headerHeight' | 'rowHeight'
type PinColumn = { key: string; label: string; width: number }
type PinTable = 'items' | 'subitems'

const props = defineProps<{
  settings: WorkItemTableSettingsUpdateRequest
  state: 'loading' | 'ready' | 'error'
  saveState: TableSettingsSaveState
  saveError?: string | undefined
  disabled: boolean
  /** 当前从左到右的可见列，第一项为恒固定的工作项名称列。 */
  pinColumns: PinColumn[]
  /** 固定区可用宽度；返回 0 表示无法测量、不限制。 */
  pinWidthBudget: () => number
  /** 子项表从左到右的可见列，第一项为恒固定的工作项名称列；不传时不显示子项列分组。 */
  subitemPinColumns?: PinColumn[] | undefined
  subitemPinWidthBudget?: (() => number) | undefined
  defaultsCount: number
  catalog: TableSettingsCatalog
  defaultColumns: Array<{ key: DefaultValueColumnKey; label: string }>
}>()
const emit = defineEmits<{ update: [patch: Partial<WorkItemTableSettingsUpdateRequest>]; retry: []; retrySave: [] }>()

const PANEL_WIDTHS: Record<Panel, number> = { menu: 240, pin: 300, coloring: 820, defaults: 480 }
const HEIGHT_FIELDS: Array<{ field: HeightField; label: string; icon: string }> = [
  { field: 'headerHeight', label: '表头高度', icon: 'M10 3v14M7 6l3-3 3 3M7 14l3 3 3-3' },
  { field: 'rowHeight', label: '行高', icon: 'M5 3v14M3 5.5 5 3l2 2.5M3 14.5 5 17l2-2.5M10 6h7M10 10h7M10 14h7' },
]
const root = ref<HTMLElement>()
const open = ref(false)
const panel = ref<Panel>('menu')
const widthBudget = ref(0)
const subitemWidthBudget = ref(0)
const openHeight = ref<HeightField | null>(null)
const popperClass = `table-settings-owner-${useId().replace(/[^\w-]/g, '-')}`

const pinnedCount = computed(() => Math.min(props.settings.pinnedColumnCount, Math.max(0, props.pinColumns.length - 1)))
const subitemPinnedCount = computed(() => Math.min(props.settings.subitemPinnedColumnCount ?? 0,
  Math.max(0, (props.subitemPinColumns?.length ?? 0) - 1)))
const totalPinnedCount = computed(() => pinnedCount.value + subitemPinnedCount.value)
const coloringCount = computed(() => props.settings.coloringRules.filter(isColoringRuleComplete).length)
const customized = computed(() => totalPinnedCount.value > 0 || coloringCount.value > 0 || props.defaultsCount > 0
  || props.settings.headerHeight !== WorkItemTableHeight.Single || props.settings.rowHeight !== WorkItemTableHeight.Single)
function pinOptionsFor(columns: PinColumn[], count: number, budget: number) {
  let width = 0
  return columns.map((column, index) => {
    width += column.width
    const pinned = index <= count
    return { key: column.key, label: column.label, index, pinned, tooWide: !pinned && budget > 0 && width > budget }
  })
}
const pinGroups = computed(() => [
  { table: 'items' as PinTable, title: '工作项列', options: pinOptionsFor(props.pinColumns, pinnedCount.value, widthBudget.value) },
  ...props.subitemPinColumns ? [{ table: 'subitems' as PinTable, title: '子项列',
    options: pinOptionsFor(props.subitemPinColumns, subitemPinnedCount.value, subitemWidthBudget.value) }] : [],
])

function toggle(): void {
  if (open.value) {
    open.value = false
    return
  }
  if (props.disabled) return
  panel.value = 'menu'
  open.value = true
}

function showPanel(next: Panel): void {
  if (props.state !== 'ready') return
  if (next === 'pin') {
    widthBudget.value = props.pinWidthBudget()
    subitemWidthBudget.value = props.subitemPinWidthBudget?.() ?? 0
  }
  panel.value = next
}

function setPinned(table: PinTable, index: number, checked: boolean): void {
  const count = checked ? index : index - 1
  emit('update', table === 'items' ? { pinnedColumnCount: count } : { subitemPinnedColumnCount: count })
}

function setHeight(field: HeightField, value: WorkItemTableHeight): void {
  emit('update', field === 'headerHeight' ? { headerHeight: value } : { rowHeight: value })
  open.value = false
}

function setHeightVisible(field: HeightField, visible: boolean): void {
  if (visible) {
    if (open.value && panel.value === 'menu') openHeight.value = field
  } else if (openHeight.value === field) {
    // 旧子菜单的延迟隐藏或离场事件不能关闭刚打开的另一个子菜单。
    openHeight.value = null
  }
}

function onDocumentPointerDown(event: PointerEvent): void {
  const target = event.target
  if (!(target instanceof Element) || root.value?.contains(target) || target.closest(`.${popperClass}`)) return
  // 固定列面板打开时允许直接拖拽列头调整顺序，菜单保持打开并实时刷新列顺序。
  if (panel.value === 'pin' && target.closest('.monday-table th.el-table__cell, .monday-table tr.work-item-group-columns')
    && root.value?.closest('.project-overview-stack') === target.closest('.project-overview-stack')) return
  open.value = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return
  if (openHeight.value) {
    event.preventDefault()
    event.stopPropagation()
    openHeight.value = null
  } else open.value = false
}

function unbind(): void {
  document.removeEventListener('pointerdown', onDocumentPointerDown, true)
  document.removeEventListener('keydown', onKeydown)
}

watch(open, value => {
  unbind()
  if (!value) { openHeight.value = null; return }
  document.addEventListener('pointerdown', onDocumentPointerDown, true)
  document.addEventListener('keydown', onKeydown)
})
watch(() => props.disabled, value => { if (value) open.value = false })
onBeforeUnmount(unbind)
</script>

<template>
  <span
    ref="root"
    class="table-settings-menu"
    @keydown.esc="onKeydown"
  >
    <!-- 只传 visible、不监听 update:visible：ElPopover 处于受控模式，外部点击由本组件判断。 -->
    <el-popover
      :visible="open"
      :persistent="false"
      placement="bottom-end"
      :width="PANEL_WIDTHS[panel]"
      :popper-class="`work-items-popover work-item-view-control ${popperClass}`"
    >
      <template #reference>
        <button
          type="button"
          class="table-settings-toolbar-button"
          :class="{ active: customized, 'table-settings-toolbar-button--unsaved': saveState === 'error' }"
          :disabled="disabled"
          :title="saveState === 'error' ? saveError : undefined"
          aria-label="表格设置"
          aria-haspopup="menu"
          :aria-expanded="open"
          @click="toggle"
        >
          <svg width="16" height="16" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <circle cx="4.5" cy="10" r="1.6" />
            <circle cx="10" cy="10" r="1.6" />
            <circle cx="15.5" cy="10" r="1.6" />
          </svg>
          <small v-if="saveState === 'error'" class="table-settings-unsaved">未保存</small>
        </button>
      </template>

      <div v-if="state !== 'ready'" class="table-settings-state" role="status">
        <span>{{ state === 'loading' ? '正在读取表格设置…' : '表格设置读取失败' }}</span>
        <button v-if="state === 'error'" type="button" class="table-settings-link" @click="emit('retry')">重试</button>
      </div>

      <div v-else-if="panel === 'menu'" class="table-settings-list" role="menu" aria-label="表格设置" @keydown.esc="onKeydown">
        <button type="button" role="menuitem" class="table-settings-item" @click="showPanel('pin')">
          <svg class="table-settings-item__icon" width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor"
            stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M7.5 3h5M8.5 3v4.5L6 11h8l-2.5-3.5V3M10 11v6" />
          </svg>
          <span>固定列<template v-if="totalPinnedCount"> / {{ totalPinnedCount }}</template></span>
        </button>
        <el-popover
          v-for="item in HEIGHT_FIELDS"
          :key="item.field"
          :visible="openHeight === item.field"
          trigger="hover"
          placement="left-start"
          :width="148"
          :show-after="80"
          :hide-after="120"
          :popper-class="`work-items-popover work-item-view-control ${popperClass}`"
          @update:visible="setHeightVisible(item.field, $event)"
        >
          <template #reference>
            <button type="button" role="menuitem" aria-haspopup="menu" class="table-settings-item">
              <svg class="table-settings-item__icon" width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor"
                stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path :d="item.icon" />
              </svg>
              <span>{{ item.label }}</span>
              <small class="table-settings-item__value">{{ heightOption(settings[item.field]).label }}</small>
            </button>
          </template>
          <div class="table-settings-list" role="menu" :aria-label="item.label" @keydown.esc="onKeydown">
            <button
              v-for="option in HEIGHT_OPTIONS"
              :key="option.value"
              type="button"
              role="menuitemradio"
              class="table-settings-item"
              :class="{ 'table-settings-item--current': settings[item.field] === option.value }"
              :aria-checked="settings[item.field] === option.value"
              @click="setHeight(item.field, option.value)"
            >
              {{ option.label }}
            </button>
          </div>
        </el-popover>
        <button type="button" role="menuitem" class="table-settings-item" @click="showPanel('coloring')">
          <svg class="table-settings-item__icon" width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor"
            stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M3.8 9.2 9 4l6 6-5.2 5.2a1.4 1.4 0 0 1-2 0l-4-4a1.4 1.4 0 0 1 0-2ZM3.5 10.2h11.3M16.5 13.2s1.5 1.7 1.5 2.7a1.5 1.5 0 0 1-3 0c0-1 1.5-2.7 1.5-2.7Z" />
          </svg>
          <span>条件着色<template v-if="coloringCount"> / {{ coloringCount }}</template></span>
        </button>
        <button type="button" role="menuitem" class="table-settings-item" @click="showPanel('defaults')">
          <svg class="table-settings-item__icon" width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor"
            stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M12.8 4.2l3 3-8.5 8.5h-3v-3ZM11.3 5.7l3 3" />
          </svg>
          <span>新建默认值<template v-if="defaultsCount"> / {{ defaultsCount }}</template></span>
        </button>
      </div>

      <section v-else-if="panel === 'pin'" class="table-settings-panel" aria-label="固定列">
        <header class="table-settings-panel__header">
          <button type="button" class="table-settings-back" aria-label="返回表格设置" @click="panel = 'menu'">‹</button>
          <strong>选择要固定的列</strong>
        </header>
        <p class="table-settings-panel__hint">勾选某列会同时固定它左侧的所有列，固定列不随左右滚动。可直接拖动列头调整顺序。</p>
        <section
          v-for="group in pinGroups"
          :key="group.table"
          class="table-settings-pin-group"
          :aria-label="group.title"
        >
          <h4 class="table-settings-pin-group__title">
            {{ group.title }}
          </h4>
          <div class="table-settings-pin-list">
            <el-tooltip
              v-for="option in group.options"
              :key="option.key"
              content="固定区域将超出表格可视宽度"
              placement="left"
              :show-after="300"
              :enterable="false"
              :disabled="!option.tooWide"
            >
              <label
                class="table-settings-pin-option"
                :class="{
                  'table-settings-pin-option--locked': option.index === 0,
                  'table-settings-pin-option--disabled': option.tooWide,
                }"
              >
                <work-item-field-glyph :field="option.key" />
                <span class="table-settings-pin-option__label">{{ option.label }}</span>
                <el-checkbox
                  :model-value="option.pinned"
                  :disabled="option.index === 0 || option.tooWide"
                  :aria-label="`固定${group.title === '子项列' ? '子项' : ''}列：${option.label}`"
                  @change="setPinned(group.table, option.index, Boolean($event))"
                />
              </label>
            </el-tooltip>
          </div>
        </section>
      </section>

      <work-item-coloring-rules-panel
        v-else-if="panel === 'coloring'"
        :rules="settings.coloringRules"
        :catalog="catalog"
        :popper-class="popperClass"
        @back="panel = 'menu'"
        @change="emit('update', { coloringRules: $event })"
      />

      <work-item-default-values-panel
        v-else
        :values="settings.defaultValues"
        :catalog="catalog"
        :columns="defaultColumns"
        :popper-class="popperClass"
        @back="panel = 'menu'"
        @change="emit('update', { defaultValues: $event })"
      />
      <div v-if="state === 'ready' && saveState !== 'saved'" class="table-settings-save-state" role="status">
        <span>{{ saveState === 'error' ? saveError : saveState === 'saving' ? '正在保存…' : '等待保存…' }}</span>
        <button v-if="saveState === 'error'" type="button" class="table-settings-link" @click="emit('retrySave')">重试保存</button>
      </div>
    </el-popover>
  </span>
</template>
