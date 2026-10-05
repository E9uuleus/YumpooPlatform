<script setup lang="ts">
import './workItemColumnHeader.css'
import { ref } from 'vue'
import MondayColumnQuickSort from './MondayColumnQuickSort.vue'
import WorkItemColumnExpandButton from './WorkItemColumnExpandButton.vue'
import WorkItemColumnHeaderMenu from './WorkItemColumnHeaderMenu.vue'
import type { ColumnMenuAction, ColumnMenuState } from './workItemColumnMenu'

withDefaults(defineProps<{
  state: ColumnMenuState
  collapsed?: boolean
  saving: boolean
  allowSave?: boolean
}>(), { collapsed: false, allowSave: true })
const emit = defineEmits<{ sort: []; clear: []; save: []; action: [action: ColumnMenuAction, anchor?: HTMLElement] }>()
const header = ref<HTMLElement>()
const menuOpen = ref(false)
function onAction(action: ColumnMenuAction): void {
  emit('action', action, header.value?.closest<HTMLElement>('th, td') ?? header.value)
}
</script>

<template>
  <work-item-column-expand-button v-if="collapsed" :label="state.label" @expand="emit('action', { type: 'collapse' })" />
  <div
    v-else
    ref="header"
    class="work-item-column-header"
    :class="{ 'work-item-column-header--menu-open': menuOpen }"
  >
    <monday-column-quick-sort
      :label="state.label"
      :direction="state.sortDirection"
      :saving="saving"
      :allow-save="allowSave"
      @sort="emit('sort')"
      @clear="emit('clear')"
      @save="emit('save')"
    />
    <work-item-column-header-menu
      class="work-item-column-header__menu"
      :state="state"
      @visible-change="menuOpen = $event"
      @action="onAction"
    />
  </div>
</template>
