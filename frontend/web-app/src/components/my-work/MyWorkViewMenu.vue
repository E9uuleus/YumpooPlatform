<script setup lang="ts">
import { ArrowDown } from '@element-plus/icons-vue'
import { ElButton, ElDropdown, ElDropdownItem as ElDropdownItemRaw, ElDropdownMenu, ElIcon } from 'element-plus'
import { computed, type DefineComponent } from 'vue'
import WorkItemActionIcon from '../projects/WorkItemActionIcon.vue'
import { myWorkViews, type MyWorkViewField } from './myWorkViews'

const props = defineProps<{ modelValue: MyWorkViewField }>()
const emit = defineEmits<{ 'update:modelValue': [field: MyWorkViewField] }>()
const ElDropdownItem = ElDropdownItemRaw as unknown as DefineComponent
const current = computed(() => myWorkViews.find(view => view.field === props.modelValue) ?? myWorkViews[0]!)
</script>

<template>
  <el-dropdown
    trigger="click"
    placement="bottom-end"
    @command="emit('update:modelValue', $event)"
  >
    <el-button
      class="my-work-view-menu__trigger"
      text
      :aria-label="`视图选项，当前为${current.label}`"
      data-tour="my-work-view"
    >
      <work-item-action-icon :name="current.icon" />
      <span class="my-work-view-menu__label">{{ current.label }}</span>
      <el-icon
        class="my-work-view-menu__caret"
        aria-hidden="true"
      >
        <arrow-down />
      </el-icon>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu class="my-work-view-menu__list">
        <el-dropdown-item
          v-for="view in myWorkViews"
          :key="view.field"
          :command="view.field"
          :class="{ 'yp-menu-item--current': view.field === modelValue }"
          :aria-current="view.field === modelValue ? 'true' : undefined"
        >
          <work-item-action-icon
            class="my-work-view-menu__icon"
            :name="view.icon"
          />
          {{ view.label }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.my-work-view-menu__label { margin-left: 6px; }
.my-work-view-menu__caret { margin-left: 4px; font-size: 12px; }
.my-work-view-menu__list { min-width: 148px; }
.my-work-view-menu__icon { margin-right: 8px; }
@media (max-width: 600px) { .my-work-view-menu__label { display: none; } }
</style>
