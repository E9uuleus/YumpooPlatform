<script setup lang="ts">
import './workItemTableSettings.css'
import { WorkItemTableHeight, type WorkItemTableSettingsUpdateRequest } from '@yumpoo/api-client'
import { ElCheckbox, ElPopover } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import WorkItemColoringRulesPanel from './WorkItemColoringRulesPanel.vue'
import WorkItemDefaultValuesPanel from './WorkItemDefaultValuesPanel.vue'
import {
  HEIGHT_OPTIONS,
  heightOption,
  isColoringRuleComplete,
  type DefaultValueColumnKey,
  type TableSettingsCatalog,
} from './workItemTableSettings'

type Panel = 'menu' | 'pin' | 'coloring' | 'defaults'
type HeightField = 'headerHeight' | 'rowHeight'

const props = defineProps<{
  settings: WorkItemTableSettingsUpdateRequest
  state: 'loading' | 'ready' | 'error'
  disabled: boolean
  /** 当前从左到右的可见列，第一项为恒固定的工作项名称列。 */
  pinColumns: Array<{ key: string; label: string; width: number }>
  /** 固定区可用宽度；返回 0 表示无法测量、不限制。 */
  pinWidthBudget: () => number
  defaultsCount: number
  catalog: TableSettingsCatalog
  defaultColumns: Array<{ key: DefaultValueColumnKey; label: string }>
}>()
const emit = defineEmits<{ update: [patch: Partial<WorkItemTableSettingsUpdateRequest>]; retry: [] }>()

const PANEL_WIDTHS: Record<Panel, number> = { menu: 240, pin: 300, coloring: 820, defaults: 480 }
const HEIGHT_FIELDS: Array<{ field: HeightField; label: string; icon: string }> = [
  { field: 'headerHeight', label: '表头高度', icon: 'M10 3v14M7 6l3-3 3 3M7 14l3 3 3-3' },
  { field: 'rowHeight', label: '行高', icon: 'M5 3v14M3 5.5 5 3l2 2.5M3 14.5 5 17l2-2.5M10 6h7M10 10h7M10 14h7' },
]
const root = ref<HTMLElement>()
const open = ref(false)
const panel = ref<Panel>('menu')
const widthBudget = ref(0)

const pinnedCount = computed(() => Math.min(props.settings.pinnedColumnCount, Math.max(0, props.pinColumns.length - 1)))
const coloringCount = computed(() => props.settings.coloringRules.filter(isColoringRuleComplete).length)
const customized = computed(() => pinnedCount.value > 0 || coloringCount.value > 0 || props.defaultsCount > 0
  || props.settings.headerHeight !== WorkItemTableHeight.Single || props.settings.rowHeight !== WorkItemTableHeight.Single)
const pinOptions = computed(() => {
  let width = 0
  return props.pinColumns.map((column, index) => {
    width += column.width
    const pinned = index <= pinnedCount.value
    return { key: column.key, label: column.label, index, pinned,
      tooWide: !pinned && widthBudget.value > 0 && width > widthBudget.value }
  })
})

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
  if (next === 'pin') widthBudget.value = props.pinWidthBudget()
  panel.value = next
}

function setPinned(index: number, checked: boolean): void {
  emit('update', { pinnedColumnCount: checked ? index : index - 1 })
}

function setHeight(field: HeightField, value: WorkItemTableHeight): void {
  emit('update', field === 'headerHeight' ? { headerHeight: value } : { rowHeight: value })
  open.value = false
}

function onDocumentPointerDown(event: PointerEvent): void {
  const target = event.target
  if (!(target instanceof Element) || root.value?.contains(target) || target.closest('.el-popper, .el-overlay')) return
  // 固定列面板打开时允许直接拖拽列头调整顺序，菜单保持打开并实时刷新列顺序。
  if (panel.value === 'pin' && target.closest('.monday-table th.el-table__cell, .monday-table tr.work-item-group-columns')) return
  open.value = false
}

function onKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') open.value = false
}

function unbind(): void {
  document.removeEventListener('pointerdown', onDocumentPointerDown, true)
  document.removeEventListener('keydown', onKeydown)
}

watch(open, value => {
  unbind()
  if (!value) return
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
  >
    <!-- 只传 visible、不监听 update:visible：ElPopover 处于受控模式，外部点击由本组件判断。 -->
    <el-popover
      :visible="open"
      placement="bottom-end"
      :width="PANEL_WIDTHS[panel]"
      popper-class="work-items-popover work-item-view-control"
    >
      <template #reference>
        <button
          type="button"
          class="table-settings-toolbar-button"
          :class="{ active: customized }"
          :disabled="disabled"
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
        </button>
      </template>

      <div v-if="state !== 'ready'" class="table-settings-state" role="status">
        <span>{{ state === 'loading' ? '正在读取表格设置…' : '表格设置读取失败' }}</span>
        <button v-if="state === 'error'" type="button" class="table-settings-link" @click="emit('retry')">重试</button>
      </div>

      <div v-else-if="panel === 'menu'" class="table-settings-list" role="menu" aria-label="表格设置">
        <button type="button" role="menuitem" class="table-settings-item" @click="showPanel('pin')">
          <svg class="table-settings-item__icon" width="18" height="18" viewBox="0 0 20 20" fill="none" stroke="currentColor"
            stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M7.5 3h5M8.5 3v4.5L6 11h8l-2.5-3.5V3M10 11v6" />
          </svg>
          <span>固定列<template v-if="pinnedCount"> / {{ pinnedCount }}</template></span>
        </button>
        <el-popover
          v-for="item in HEIGHT_FIELDS"
          :key="item.field"
          trigger="hover"
          placement="left-start"
          :width="148"
          :show-after="80"
          :hide-after="120"
          popper-class="work-items-popover work-item-view-control"
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
          <div class="table-settings-list" role="menu" :aria-label="item.label">
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
        <div class="table-settings-pin-list">
          <el-checkbox
            v-for="option in pinOptions"
            :key="option.key"
            class="table-settings-pin-option"
            :model-value="option.pinned"
            :disabled="option.index === 0 || option.tooWide"
            :title="option.tooWide ? '固定区域将超出表格可视宽度' : undefined"
            @change="setPinned(option.index, Boolean($event))"
          >
            {{ option.label }}
          </el-checkbox>
        </div>
      </section>

      <work-item-coloring-rules-panel
        v-else-if="panel === 'coloring'"
        :rules="settings.coloringRules"
        :catalog="catalog"
        @back="panel = 'menu'"
        @change="emit('update', { coloringRules: $event })"
      />

      <work-item-default-values-panel
        v-else
        :values="settings.defaultValues"
        :catalog="catalog"
        :columns="defaultColumns"
        @back="panel = 'menu'"
        @change="emit('update', { defaultValues: $event })"
      />
    </el-popover>
  </span>
</template>
