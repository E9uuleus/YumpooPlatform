<script setup lang="ts">
import { ref, shallowRef } from 'vue'
import { ElDropdown, ElDropdownItem, ElDropdownMenu, ElPopover } from 'element-plus'
import WorkItemActionIcon from './WorkItemActionIcon.vue'
import type { ColumnMenuAction, ColumnMenuState } from './workItemColumnMenu'

const props = defineProps<{ state: ColumnMenuState }>()
const emit = defineEmits<{ action: [action: ColumnMenuAction]; visibleChange: [visible: boolean] }>()
const open = ref(false)
const submenu = ref<'settings' | 'sort'>()
const submenuAnchor = shallowRef<HTMLElement>()
const dropdown = ref<InstanceType<typeof ElDropdown>>()

function visibility(visible: boolean): void {
  open.value = visible
  emit('visibleChange', visible)
  if (!visible) submenu.value = undefined
}
function showSubmenu(name: 'settings' | 'sort', event: Event): void {
  if (name === 'settings' && props.state.settings === 'unavailable') return
  if (name === 'sort' && props.state.sortDisabled) return
  if (event.currentTarget instanceof HTMLElement) submenuAnchor.value = event.currentTarget
  submenu.value = name
}
function run(action: ColumnMenuAction): void {
  submenu.value = undefined
  dropdown.value?.handleClose()
  emit('action', action)
}
</script>

<template>
  <div
    class="work-item-column-menu"
    :class="{ 'work-item-column-menu--open': open }"
    @pointerdown.stop
    @click.stop
  >
    <el-dropdown
      ref="dropdown"
      trigger="click"
      placement="bottom-start"
      :hide-on-click="false"
      :persistent="false"
      popper-class="work-item-column-menu-popper work-item-view-control"
      @visible-change="visibility"
    >
      <button
        type="button"
        class="work-item-column-menu__trigger"
        :aria-label="`${state.label}列菜单`"
        :aria-expanded="open"
        aria-haspopup="menu"
      >
        <work-item-action-icon name="more" />
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <el-dropdown-item
            class="work-item-column-menu__submenu"
            :class="{ 'is-open': submenu === 'settings' }"
            :disabled="state.settings === 'unavailable'"
            :title="state.settings === 'unavailable' ? '暂仅支持状态、优先级和工作项类别列' : undefined"
            @pointermove="showSubmenu('settings', $event)"
            @click="showSubmenu('settings', $event)"
            @keydown.right.stop.prevent="showSubmenu('settings', $event)"
          >
            <work-item-action-icon name="settings" />设置
            <work-item-action-icon name="chevron" class="work-item-column-menu__arrow" />
          </el-dropdown-item>
          <el-dropdown-item divided @pointermove="submenu = undefined" @click="run({ type: 'filter' })">
            <work-item-action-icon name="filter" />筛选
          </el-dropdown-item>
          <el-dropdown-item
            class="work-item-column-menu__submenu"
            :class="{ 'is-open': submenu === 'sort' }"
            :disabled="state.sortDisabled"
            @pointermove="showSubmenu('sort', $event)"
            @click="showSubmenu('sort', $event)"
            @keydown.right.stop.prevent="showSubmenu('sort', $event)"
          >
            <work-item-action-icon name="sort" />排序
            <work-item-action-icon name="chevron" class="work-item-column-menu__arrow" />
          </el-dropdown-item>
          <el-dropdown-item :disabled="!state.collapsible" @pointermove="submenu = undefined" @click="run({ type: 'collapse' })">
            <work-item-action-icon name="collapse" />折叠
          </el-dropdown-item>
          <el-dropdown-item
            :disabled="!state.groupField || state.groupDisabled"
            :title="state.groupField ? undefined : '该列暂不支持分组'"
            @pointermove="submenu = undefined"
            @click="run({ type: 'group' })"
          >
            <work-item-action-icon name="group" />{{ state.groupedByThis ? '取消分组' : '分组依据' }}
          </el-dropdown-item>
          <el-popover
            v-if="submenuAnchor"
            :visible="Boolean(submenu)"
            virtual-triggering
            :virtual-ref="submenuAnchor"
            placement="right-start"
            :fallback-placements="['left-start']"
            :width="200"
            :offset="6"
            :show-arrow="false"
            :teleported="false"
            popper-class="work-item-column-submenu"
          >
            <div v-if="submenu === 'settings'" class="work-item-column-submenu__list" role="menu">
              <button
                type="button"
                role="menuitem"
                :disabled="state.settings !== 'editable'"
                :title="state.settings === 'readonly' ? '当前角色只能查看标签' : undefined"
                @click="run({ type: 'settings' })"
              >
                编辑标签与颜色
              </button>
            </div>
            <div v-else-if="submenu === 'sort'" class="work-item-column-submenu__list" role="menu">
              <button type="button" role="menuitem" :class="{ active: state.sortDirection === 'ASC' }" @click="run({ type: 'sort', direction: 'ASC' })">升序</button>
              <button type="button" role="menuitem" :class="{ active: state.sortDirection === 'DESC' }" @click="run({ type: 'sort', direction: 'DESC' })">降序</button>
              <button v-if="state.sortDirection" type="button" role="menuitem" @click="run({ type: 'sort', direction: null })">清除排序</button>
            </div>
          </el-popover>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<style>
.work-item-column-menu { display: flex; }
.work-item-column-menu-popper .el-dropdown-menu { min-width: 220px; padding: 8px; }
.work-item-column-menu-popper .el-scrollbar, .work-item-column-menu-popper .el-scrollbar__wrap { overflow: visible; }
.work-item-column-menu-popper .el-dropdown-menu__item { min-height: 34px; padding: 0 10px; gap: 10px; border-radius: 4px; font-size: 14px; }
.work-item-column-menu-popper .el-dropdown-menu__item--divided { margin: 7px 0 0; }
.work-item-column-menu-popper .work-item-column-menu__submenu.is-open { background: var(--yp-bg-hover); }
.work-item-column-menu__arrow { width: 14px; height: 14px; margin-left: auto; }
.work-item-column-submenu.el-popover { padding: 8px; border-radius: 8px; }
.work-item-column-submenu__list { display: grid; gap: 2px; }
.work-item-column-submenu__list button { display: flex; min-height: 34px; align-items: center; padding: 0 10px; border: 0; border-radius: 4px; background: transparent; color: var(--yp-text-primary); font: inherit; font-size: 14px; text-align: left; cursor: pointer; }
.work-item-column-submenu__list button:hover:not(:disabled),
.work-item-column-submenu__list button.active { background: var(--yp-bg-hover); }
.work-item-column-submenu__list button.active { color: var(--yp-action-primary); }
.work-item-column-submenu__list button:disabled { color: var(--yp-text-disabled); cursor: not-allowed; }
</style>
