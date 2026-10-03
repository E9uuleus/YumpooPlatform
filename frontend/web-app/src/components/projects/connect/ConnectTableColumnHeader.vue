<script setup lang="ts">
import type { UnwrapRef } from 'vue'
import type { ConnectColumnKey, ConnectTableColumn } from './connectColumnKeys'
import type { useConnectTable } from './useConnectTable'
import ConnectColumnHeader from './ConnectColumnHeader.vue'

defineProps<{
  column: ConnectTableColumn
  table: Pick<UnwrapRef<ReturnType<typeof useConnectTable>>, 'canManage' | 'canDelete' | 'edit' | 'requestDelete' | 'resizeColumn'>
}>()
const emit = defineEmits<{ hide: [key: ConnectColumnKey] }>()
</script>

<template>
  <connect-column-header
    :label="column.label"
    :kind="column.kind"
    :can-manage="table.canManage"
    :can-delete="table.canDelete"
    :column-key="column.key"
    :width="column.width"
    :min-width="column.minWidth"
    @edit="table.edit(column.column)"
    @hide="emit('hide', column.key)"
    @delete="column.column && table.requestDelete(column.column)"
    @resize="(width, save) => table.resizeColumn(column.key, width, save)"
  />
</template>
