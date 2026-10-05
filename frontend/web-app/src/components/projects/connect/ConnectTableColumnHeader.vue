<script setup lang="ts">
import { computed, type UnwrapRef } from 'vue'
import type { ConnectColumnKey, ConnectTableColumn } from './connectColumnKeys'
import type { ConnectTargetChoice, useConnectTable } from './useConnectTable'
import ConnectColumnHeader from './ConnectColumnHeader.vue'
import ConnectColumnSetupPopover from './ConnectColumnSetupPopover.vue'

const props = withDefaults(defineProps<{
  column: ConnectTableColumn
  table: Pick<UnwrapRef<ReturnType<typeof useConnectTable>>, 'canManage' | 'canDelete' | 'openSettings' | 'requestDelete' | 'resizeColumn'
    | 'rename' | 'columnNames' | 'setup' | 'celebrated' | 'closeSetup' | 'connectProjects' | 'projectName'>
  projectId: string
  /** Only the header in the table head hosts the setup popover; group header rows repeat the label without it. */
  primary?: boolean
  collapsible?: boolean
}>(), { primary: true, collapsible: false })
const emit = defineEmits<{ hide: [key: ConnectColumnKey]; collapse: [key: ConnectColumnKey] }>()
const setupMode = computed(() => props.primary && props.table.setup?.key === props.column.key ? props.table.setup.mode : undefined)
function submit(projects: ConnectTargetChoice[]) { return props.table.connectProjects(projects, props.column.column) }
</script>

<template>
  <div
    class="connect-table-header"
    :class="{ 'connect-table-header--celebrate': table.celebrated === column.key, 'connect-table-header--draft': column.kind === 'draft' }"
  >
    <connect-column-header
      :label="column.label"
      :kind="column.kind"
      :can-manage="table.canManage"
      :collapsible="collapsible"
      :can-delete="table.canDelete"
      :column-key="column.key"
      :width="column.width"
      :min-width="column.minWidth"
      :reverse-hint="column.reverse && `双向连接：来自「${column.reverse.projectName}」的「${column.reverse.columnName}」`"
      :taken-names="table.columnNames"
      :rename="column.column ? name => table.rename(column.column!, name) : undefined"
      @edit="column.column && table.openSettings(column.column)"
      @hide="emit('hide', column.key)"
      @collapse="emit('collapse', column.key)"
      @delete="column.column && table.requestDelete(column.column)"
      @resize="(width, save) => table.resizeColumn(column.key, width, save)"
    />
    <connect-column-setup-popover
      v-if="setupMode"
      :visible="true"
      :mode="setupMode"
      :column="column.column"
      :project-id="projectId"
      :project-name="table.projectName"
      :submit="submit"
      @update:visible="value => !value && table.closeSetup()"
    />
  </div>
</template>

<style scoped>
.connect-table-header { position: relative; width: 100%; }
.connect-table-header--draft { animation: connect-draft-header-in var(--yp-motion-overlay) var(--yp-ease-standard) both; }
.connect-table-header--celebrate::after { position: absolute; inset: 2px -8px; border-radius: var(--yp-radius-sm); background: linear-gradient(100deg, transparent 0%, color-mix(in srgb, var(--yp-link) 22%, transparent) 45%, transparent 70%); background-size: 220% 100%; content: ''; pointer-events: none; animation: connect-header-sweep 900ms var(--yp-ease-standard) both; }
@keyframes connect-draft-header-in { from { opacity: 0; transform: translateY(-4px); } }
@keyframes connect-header-sweep { from { background-position: 120% 0; opacity: 1; } 80% { opacity: 1; } to { background-position: -110% 0; opacity: 0; } }
@media (prefers-reduced-motion: reduce) {
  .connect-table-header--draft { animation: none; }
  .connect-table-header--celebrate::after { animation: none; display: none; }
}
</style>
