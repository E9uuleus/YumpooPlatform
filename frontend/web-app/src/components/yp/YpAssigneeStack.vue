<script setup lang="ts">
import { ElTooltip } from 'element-plus'
import { computed } from 'vue'
import YpAssignee from './YpAssignee.vue'

const props = withDefaults(defineProps<{
  assignees: ReadonlyArray<{ userId: string; displayName: string }>
  max?: number
}>(), { max: 2 })
const visible = computed(() => props.assignees.length > props.max ? props.assignees.slice(0, props.max - 1) : props.assignees)
const overflow = computed(() => props.assignees.length - visible.value.length)
const names = computed(() => props.assignees.map(item => item.displayName).join('、'))
</script>

<template>
  <yp-assignee v-if="!assignees.length" :show-name="false" size="table" />
  <el-tooltip v-else :content="names" placement="top">
    <span class="yp-assignee-stack" :aria-label="names">
      <yp-assignee
        v-for="assignee in visible"
        :key="assignee.userId"
        :user-id="assignee.userId"
        :display-name="assignee.displayName"
        :show-name="false"
        size="table"
        tooltip-disabled
      />
      <span v-if="overflow" class="yp-assignee-stack__more">+{{ overflow }}</span>
    </span>
  </el-tooltip>
</template>

<style scoped>
.yp-assignee-stack { display: inline-flex; align-items: center; }
.yp-assignee-stack > * + * { margin-left: -6px; }
.yp-assignee-stack :deep(.el-avatar) { box-shadow: 0 0 0 2px var(--yp-bg-surface); }
.yp-assignee-stack__more { display: inline-grid; min-width: 24px; height: 24px; padding: 0 4px; place-items: center; box-sizing: border-box; border-radius: var(--yp-radius-pill); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font-size: var(--yp-type-caption-size); font-weight: 600; box-shadow: 0 0 0 2px var(--yp-bg-surface); }
</style>
