<script setup lang="ts">
import { computed } from 'vue'
import type { WorkItemConnection, WorkItemConnectionCell } from '@yumpoo/api-client'
import type { ConnectSourceItem, ConnectTableColumn } from './connectColumnKeys'
import ConnectCell from './ConnectCell.vue'
import ReverseConnectCell from './ReverseConnectCell.vue'

const props = defineProps<{ column: ConnectTableColumn; item: ConnectSourceItem; cell?: WorkItemConnectionCell | undefined; readOnly: boolean }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection, side: 'source' | 'target'] }>()
const outgoing = computed(() => props.cell?.outgoing.find(value => value.columnId === props.column.column?.id)?.connections ?? [])
const reverse = computed(() => props.cell?.incomingByColumn.find(value => value.columnId === props.column.reverse?.columnId))
</script>

<template>
  <connect-cell
    v-if="column.kind === 'connect' && column.column"
    :item="item"
    :column="column.column"
    :connections="outgoing"
    :read-only="readOnly"
    @open-card="emit('openCard', $event, 'source')"
  />
  <reverse-connect-cell
    v-else-if="column.kind === 'reverse' && column.reverse"
    :item="item"
    :reverse="column.reverse"
    :connections="reverse?.connections ?? []"
    :total="reverse?.total ?? 0"
    :read-only="readOnly"
    @open-card="emit('openCard', $event, 'target')"
  />
</template>
