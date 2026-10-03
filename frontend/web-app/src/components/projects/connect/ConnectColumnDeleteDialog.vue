<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElButton, ElDialog, ElMessage } from 'element-plus'
import type { ConnectColumn } from '@yumpoo/api-client'
import type { ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'

const props = defineProps<{ column?: ConnectColumn | undefined }>()
const emit = defineEmits<{ close: []; deleted: [] }>()
const context = useConnectContext()
const busy = ref(false), problem = ref<ApiProblem>()
watch(() => props.column?.id, () => { problem.value = undefined })
function close() { if (!busy.value) emit('close') }
async function remove() {
  if (!props.column || busy.value) return
  busy.value = true; problem.value = undefined
  try {
    const count = await context.deleteColumn(context.catalog.value?.items.find(column => column.id === props.column?.id) ?? props.column)
    ElMessage.success(`已删除连接列，解除 ${count} 个连接`); emit('deleted'); emit('close')
  } catch (reason) { problem.value = await toConnectProblem(reason) } finally { busy.value = false }
}
</script>

<template>
  <el-dialog
    :model-value="Boolean(column)"
    title="删除连接列"
    width="min(480px, calc(100vw - 32px))"
    align-center
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    :before-close="close"
  >
    <inline-problem
      v-if="problem"
      :problem="problem"
    />
    <p>删除连接列「{{ column?.name }}」？将同时解除该列全部连接，不会删除任何工作项。</p>
    <template #footer>
      <el-button
        :disabled="busy"
        @click="close"
      >
        取消
      </el-button><el-button
        type="danger"
        :loading="busy"
        @click="remove"
      >
        删除列
      </el-button>
    </template>
  </el-dialog>
</template>
