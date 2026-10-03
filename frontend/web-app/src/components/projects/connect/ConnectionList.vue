<script setup lang="ts">
import { ref } from 'vue'
import { ElIcon, ElMessage, ElPopconfirm } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import type { WorkItemConnection } from '@yumpoo/api-client'
import { problemMessage } from '../../../api/problems'
import { workItemLabelColorValue } from '../workItemLabelColors'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'

const props = defineProps<{ connections: WorkItemConnection[]; perspective: 'source' | 'target'; readOnly: boolean }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection]; changed: [] }>()
const context = useConnectContext()
const busy = ref<string>()
const card = (connection: WorkItemConnection) => props.perspective === 'source' ? connection.target : connection.source
async function unlink(connection: WorkItemConnection) {
  if (busy.value || props.readOnly || !connection.capabilities.canUnlink) return
  busy.value = connection.id
  try { await context.unlink(connection); emit('changed'); ElMessage.success('已解除连接') }
  catch (reason) { ElMessage.error(problemMessage(await toConnectProblem(reason))) } finally { busy.value = undefined }
}
</script>

<template>
  <div class="connection-list">
    <div
      v-for="connection in connections"
      :key="connection.id"
      class="connection-list__row"
    >
      <button
        type="button"
        class="connection-list__open"
        :aria-label="`打开连接：${card(connection).title}`"
        @click.stop="emit('openCard', connection)"
      >
        <span
          class="connection-list__dot"
          :style="{ background: workItemLabelColorValue(card(connection).status.colorToken) }"
        />
        <span class="connection-list__text"><strong :class="{ 'connection-list__archived': card(connection).archived }">{{ card(connection).title }}</strong>
          <small>{{ card(connection).itemNo }} · {{ card(connection).projectName }}<span v-if="card(connection).archived"> · 已归档</span></small></span>
      </button>
      <el-popconfirm
        v-if="!readOnly && connection.capabilities.canUnlink"
        title="解除连接？对应工作项不会被删除。"
        confirm-button-text="解除连接"
        cancel-button-text="取消"
        @confirm="unlink(connection)"
      >
        <template #reference>
          <button
            type="button"
            class="connection-list__unlink"
            :disabled="Boolean(busy)"
            :aria-label="`解除连接：${card(connection).title}`"
            @click.stop
          >
            <el-icon><close /></el-icon>
          </button>
        </template>
      </el-popconfirm>
    </div>
    <p
      v-if="!connections.length"
      class="connection-list__empty"
    >
      还没有连接
    </p>
  </div>
</template>

<style scoped>
.connection-list { display: grid; gap: 2px; }
.connection-list__row { display: flex; min-width: 0; align-items: center; gap: 4px; border-radius: var(--yp-radius-sm); }
.connection-list__row:hover { background: var(--yp-bg-hover); }
.connection-list__open { display: flex; flex: 1; min-width: 0; min-height: 44px; align-items: center; gap: 8px; padding: 6px; border: 0; background: transparent; color: var(--yp-text-primary); text-align: left; cursor: pointer; }
.connection-list__dot { width: 8px; height: 8px; flex-shrink: 0; border-radius: var(--yp-radius-pill); }
.connection-list__text { display: grid; min-width: 0; gap: 2px; }
.connection-list__text strong { overflow: hidden; font-size: 13px; font-weight: 400; text-overflow: ellipsis; white-space: nowrap; }
.connection-list__text small { font-size: 11px; color: var(--yp-text-muted); }
.connection-list__archived { color: var(--yp-text-muted); }
.connection-list__unlink { display: flex; width: 26px; height: 26px; align-items: center; justify-content: center; border: 0; border-radius: var(--yp-radius-sm); background: transparent; color: var(--yp-text-muted); cursor: pointer; }
.connection-list__unlink:hover { background: var(--yp-bg-sunken); color: var(--yp-text-primary); }
.connection-list__empty { margin: 0; padding: 10px; border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); color: var(--yp-text-muted); font-size: 12px; }
</style>
