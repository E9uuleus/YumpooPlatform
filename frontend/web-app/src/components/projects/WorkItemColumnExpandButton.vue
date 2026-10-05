<script setup lang="ts">
import { ElTooltip } from 'element-plus'
import WorkItemActionIcon from './WorkItemActionIcon.vue'

defineProps<{ label: string }>()
const emit = defineEmits<{ expand: [] }>()
</script>

<template>
  <el-tooltip :content="`展开${label}列`" placement="top" :show-after="320">
    <button
      type="button"
      class="work-item-column-expand"
      :aria-label="`展开${label}列`"
      @pointerdown.stop
      @click.stop="emit('expand')"
    >
      <work-item-action-icon name="expand" />
    </button>
  </el-tooltip>
</template>

<style scoped>
.work-item-column-expand { display: flex; width: 100%; height: 36px; align-items: center; justify-content: center; padding: 0; border: 0; background: transparent; color: var(--yp-text-secondary); cursor: pointer; }
.work-item-column-expand:hover,
.work-item-column-expand:focus-visible { background: var(--yp-bg-hover); color: var(--yp-action-primary); outline: none; }
</style>
