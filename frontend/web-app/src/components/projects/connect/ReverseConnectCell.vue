<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import type { ConnectColumnIncoming, WorkItemConnection } from '@yumpoo/api-client'
import type { ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import type { ConnectSourceItem } from './connectColumnKeys'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'
import ConnectCell from './ConnectCell.vue'
import ConnectionList from './ConnectionList.vue'

const props = defineProps<{ item: ConnectSourceItem; reverse: ConnectColumnIncoming; connections: WorkItemConnection[]; total: number; readOnly: boolean }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection]; changed: [] }>()
const context = useConnectContext()
const extra = ref<WorkItemConnection[]>([]), total = ref(props.total), nextPage = ref(1), loading = ref(false), problem = ref<ApiProblem>()
const connections = computed(() => [...new Map([...props.connections, ...extra.value].filter(connection => connection.active)
  .map(connection => [connection.id, connection])).values()])
let controller: AbortController | undefined, revision = 0
watch(() => [props.item.id, props.reverse.columnId, props.connections, props.total], () => {
  revision++; controller?.abort(); extra.value = []; total.value = props.total
  nextPage.value = props.connections.length >= 50 ? 1 : 0; loading.value = false; problem.value = undefined
}, { immediate: true })
onBeforeUnmount(() => { revision++; controller?.abort() })
async function loadMore() {
  if (loading.value || connections.value.length >= total.value) return
  controller?.abort(); controller = new AbortController()
  const current = ++revision
  loading.value = true; problem.value = undefined
  try {
    const result = await context.incoming(props.item.id, nextPage.value, controller.signal, props.reverse.columnId)
    if (current !== revision) return
    extra.value = [...extra.value, ...result.items]; total.value = result.totalElements; nextPage.value = result.page + 1
  } catch (reason) { if (current === revision) problem.value = await toConnectProblem(reason) }
  finally { if (current === revision) loading.value = false }
}
</script>

<template>
  <connect-cell
    :item="item"
    :connections="connections"
    :incoming-total="total"
    :read-only="readOnly"
    @open-card="emit('openCard', $event)"
  >
    <template #popover="{ openCard }">
      <section
        class="reverse-connect-list"
        :aria-label="`${reverse.projectName}连接列表`"
      >
        <strong>{{ reverse.projectName }} · {{ reverse.columnName }}</strong>
        <div class="reverse-connect-list__items">
          <connection-list
            :connections="connections"
            perspective="target"
            :read-only="readOnly"
            @open-card="openCard"
            @changed="emit('changed')"
          />
        </div>
        <inline-problem
          v-if="problem"
          :problem="problem"
        />
        <el-button
          v-if="connections.length < total"
          text
          :loading="loading"
          @click="loadMore"
        >
          {{ problem ? '重试加载' : '加载更多' }}
        </el-button>
      </section>
    </template>
  </connect-cell>
</template>

<style scoped>
.reverse-connect-list { display: grid; gap: 8px; color: var(--yp-text-primary); font-size: 13px; }
.reverse-connect-list__items { max-height: 360px; overflow-y: auto; }
</style>
