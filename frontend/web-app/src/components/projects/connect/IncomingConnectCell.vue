<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElButton } from 'element-plus'
import type { WorkItemConnection } from '@yumpoo/api-client'
import type { ApiProblem } from '../../../api/problems'
import InlineProblem from '../../InlineProblem.vue'
import type { ConnectSourceItem } from './connectColumnKeys'
import { toConnectProblem } from './connectProblems'
import { useConnectContext } from './useConnectColumns'
import ConnectCell from './ConnectCell.vue'
import ConnectionList from './ConnectionList.vue'

const props = defineProps<{ item: ConnectSourceItem; incoming: WorkItemConnection[]; incomingTotal: number; readOnly: boolean }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection]; changed: [] }>()
const context = useConnectContext()
const extra = ref<WorkItemConnection[]>([]), total = ref(props.incomingTotal), nextPage = ref(1), loading = ref(false), problem = ref<ApiProblem>()
const connections = computed(() => [...new Map([...props.incoming, ...extra.value].filter(connection => connection.active).map(connection => [connection.id, connection])).values()])
const groups = computed(() => {
  const groups = new Map<string, { label: string; connections: WorkItemConnection[] }>()
  for (const connection of connections.value) {
    const key = `${connection.source.projectId}:${connection.columnId}`
    if (!groups.has(key)) groups.set(key, { label: `${connection.source.projectName} · ${connection.columnName}`, connections: [] })
    groups.get(key)!.connections.push(connection)
  }
  return [...groups.entries()]
})
let controller: AbortController | undefined, revision = 0
watch(() => [props.item.id, props.incoming, props.incomingTotal], () => {
  revision++; controller?.abort(); extra.value = []; total.value = props.incomingTotal
  nextPage.value = props.incoming.length >= 50 ? 1 : 0; loading.value = false; problem.value = undefined
}, { immediate: true })
onBeforeUnmount(() => { revision++; controller?.abort() })
async function loadMore() {
  if (loading.value || connections.value.length >= total.value) return
  controller?.abort(); controller = new AbortController()
  const current = ++revision
  loading.value = true; problem.value = undefined
  try {
    const result = await context.incoming(props.item.id, nextPage.value, controller.signal)
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
        class="incoming-connect-list"
        aria-label="被连接列表"
      >
        <strong>被连接</strong>
        <div class="incoming-connect-list__groups">
          <section
            v-for="[key, group] in groups"
            :key="key"
          >
            <h3>{{ group.label }}</h3>
            <connection-list
              :connections="group.connections"
              perspective="target"
              :read-only="readOnly"
              @open-card="openCard"
              @changed="emit('changed')"
            />
          </section>
          <p v-if="!connections.length">
            还没有连接
          </p>
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
.incoming-connect-list { display: grid; gap: 8px; color: var(--yp-text-primary); font-size: 13px; }
.incoming-connect-list__groups { max-height: 360px; overflow-y: auto; }
.incoming-connect-list h3 { margin: 8px 0 4px; padding-bottom: 6px; border-bottom: 1px solid var(--yp-border-subtle); color: var(--yp-text-muted); font-size: 12px; font-weight: 500; }
.incoming-connect-list p { color: var(--yp-text-muted); }
</style>
