<script setup lang="ts">
import { ElCheckbox } from 'element-plus'
import { computed, inject, onBeforeUnmount, onMounted } from 'vue'
import { workItemBatchEditScopeKey } from './workItemBatchEdit'

/** 「只改此项」只对当前打开的弹层有效：弹层内容打开时挂载、关闭时卸载，两处都恢复为同步修改。 */
const props = defineProps<{ itemId: string }>()
const scope = inject(workItemBatchEditScopeKey, undefined)
const count = computed(() => scope?.count(props.itemId) ?? 0)
function resetSync(): void { if (scope) scope.sync.value = true }
onMounted(resetSync)
onBeforeUnmount(resetSync)
</script>

<template>
  <div v-if="scope && count > 1" class="work-item-batch-edit-scope">
    <el-checkbox v-model="scope.sync.value" size="small">同步修改已选的 {{ count }} 项</el-checkbox>
  </div>
</template>

<style scoped>
.work-item-batch-edit-scope { display: flex; align-items: center; padding: 6px 12px; border-bottom: 1px solid var(--yp-border-default); white-space: nowrap; }
.work-item-batch-edit-scope--inset { margin-bottom: 8px; padding: 0 0 6px; }
.work-item-batch-edit-scope :deep(.el-checkbox__label) { color: var(--yp-text-secondary); font-size: 12px; }
</style>
