<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElIcon, ElPopover } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import type { ConnectColumn, WorkItemConnection } from '@yumpoo/api-client'
import type { ConnectSourceItem } from './connectColumnKeys'
import ConnectionChip from './ConnectionChip.vue'
import ConnectCellPopover from './ConnectCellPopover.vue'
import ConnectCreateDialog from './ConnectCreateDialog.vue'

const props = defineProps<{ item: ConnectSourceItem; column?: ConnectColumn | undefined; connections: WorkItemConnection[]; readOnly: boolean; incomingTotal?: number | undefined }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection]; changed: [] }>()
const open = ref(false), root = ref<HTMLElement>(), measure = ref<HTMLElement>(), opener = ref<HTMLButtonElement>()
const visibleCount = ref(0), createTarget = ref(''), createTitle = ref(''), createOpen = ref(false)
const showProject = computed(() => !props.column || props.column.targets.length > 1)
const total = computed(() => props.incomingTotal ?? props.connections.length)
const canOpenPopover = computed(() => total.value > 0 || (!props.readOnly && Boolean(props.column)))
const hiddenCount = computed(() => Math.max(0, total.value - visibleCount.value))
const card = (connection: WorkItemConnection) => props.column ? connection.target : connection.source
const tooltip = (connection: WorkItemConnection) => props.column ? undefined : `来自 ${connection.source.projectName} 的「${connection.columnName}」`
let observer: ResizeObserver | undefined, lastWidth = -1
function measureChips(force = false) {
  const width = root.value?.clientWidth ?? 0
  if (!force && width === lastWidth) return
  lastWidth = width
  const widths = Array.from(measure.value?.children ?? []).map(element => element.getBoundingClientRect().width)
  const allWidth = widths.reduce((sum, value) => sum + value, 0) + Math.max(0, widths.length - 1) * 4
  if (total.value === props.connections.length && allWidth <= width) { visibleCount.value = widths.length; return }
  const countWidth = 22 + String(total.value).length * 7
  let used = 0, count = 0
  for (const chipWidth of widths) {
    if (used + chipWidth + 4 + countWidth > width) break
    used += chipWidth + 4; count++
  }
  visibleCount.value = count
}
onMounted(() => {
  observer = new ResizeObserver(() => measureChips())
  if (root.value) observer.observe(root.value)
  void nextTick(() => measureChips(true))
})
watch(() => [props.connections.map(connection => `${connection.id}:${card(connection).title}:${card(connection).projectName}`).join('|'), showProject.value, total.value], () => { void nextTick(() => measureChips(true)) })
watch(() => props.item.id, () => { open.value = false; createOpen.value = false })
watch(canOpenPopover, allowed => { if (!allowed) open.value = false })
onBeforeUnmount(() => observer?.disconnect())
function close() { open.value = false; void nextTick(() => opener.value?.focus({ preventScroll: true })) }
function openCard(connection: WorkItemConnection) { open.value = false; emit('openCard', connection) }
function requestCreate(targetProjectId: string, title: string) { createTarget.value = targetProjectId; createTitle.value = title; createOpen.value = true; open.value = false }
function created() { createOpen.value = false; close(); emit('changed') }
function focusPopover() { void nextTick(() => { if (!props.column) document.getElementById(`incoming-${props.item.id}`)?.querySelector<HTMLElement>('button')?.focus() }) }
</script>

<template>
  <el-popover
    v-model:visible="open"
    trigger="click"
    placement="bottom-start"
    :width="380"
    :disabled="!canOpenPopover"
    popper-class="connect-cell-popper"
    @after-enter="focusPopover"
  >
    <template #reference>
      <div
        ref="root"
        class="connect-cell"
        :class="{ 'connect-cell--writable': !readOnly && column }"
        @click.stop
        @keydown.esc.stop.prevent="close"
      >
        <button
          v-if="canOpenPopover"
          ref="opener"
          type="button"
          class="connect-cell__open"
          :aria-label="`${column?.name ?? '双向连接'}：${item.title}，${total} 个连接`"
          :aria-expanded="open"
          @click.stop="open = !open"
        >
          <el-icon
            v-if="!total && !readOnly && column"
            class="connect-cell__plus"
          >
            <plus />
          </el-icon>
        </button>
        <div class="connect-cell__chips">
          <connection-chip
            v-for="connection in connections.slice(0, visibleCount)"
            :key="connection.id"
            :card="card(connection)"
            :show-project="showProject"
            :tooltip="tooltip(connection)"
            @click="openCard(connection)"
          />
          <button
            v-if="hiddenCount"
            type="button"
            class="connect-cell__count"
            :aria-label="`查看其余 ${hiddenCount} 个连接`"
            @click.stop="open = !open"
          >
            +{{ hiddenCount }}
          </button>
        </div>
        <div
          ref="measure"
          class="connect-cell__measure"
          aria-hidden="true"
          inert
        >
          <connection-chip
            v-for="connection in connections"
            :key="connection.id"
            :card="card(connection)"
            :show-project="showProject"
            tabindex="-1"
          />
        </div>
      </div>
    </template>
    <connect-cell-popover
      v-if="open && column"
      :item="item"
      :column="column"
      :connections="connections"
      :read-only="readOnly"
      @open-card="openCard"
      @request-create="requestCreate"
      @changed="emit('changed')"
      @close="close"
    />
    <div
      v-else-if="open"
      :id="`incoming-${item.id}`"
      tabindex="-1"
      @keydown.esc.stop.prevent="close"
    >
      <slot
        name="popover"
        :open-card="openCard"
      />
    </div>
  </el-popover>
  <connect-create-dialog
    v-if="column && createOpen"
    v-model:open="createOpen"
    :column="column"
    :target-project-id="createTarget"
    :source-item="item"
    :initial-title="createTitle"
    @created="created"
  />
</template>

<style scoped>
.connect-cell { position: relative; display: flex; align-items: center; width: 100%; min-width: 0; height: 34px; }
.connect-cell__open { position: absolute; inset: 0; display: flex; width: 100%; align-items: center; justify-content: center; padding: 0; border: 0; background: transparent; color: var(--yp-text-muted); cursor: pointer; }
.connect-cell__plus { font-size: 16px; opacity: 0; }
.connect-cell--writable:hover .connect-cell__plus, .connect-cell__open:focus-visible .connect-cell__plus { opacity: 1; }
.connect-cell__chips { display: flex; position: relative; gap: 4px; max-width: 100%; align-items: center; pointer-events: none; }
.connect-cell__chips :deep(button) { pointer-events: auto; }
.connect-cell__count { display: inline-flex; flex-shrink: 0; height: 22px; align-items: center; padding: 0 7px; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-pill); background: var(--yp-bg-sunken); color: var(--yp-text-secondary); font-size: 12px; cursor: pointer; }
.connect-cell__count:hover { background: var(--yp-bg-hover); }
.connect-cell__measure { position: absolute; display: flex; width: max-content; height: 0; overflow: hidden; visibility: hidden; pointer-events: none; }
.connect-cell__open:focus-visible { outline: 2px solid var(--yp-action-primary); outline-offset: -2px; }
</style>

<style>
.connect-cell-popper { max-width: calc(100vw - 32px); max-height: calc(100vh - 32px); overflow-y: auto; box-sizing: border-box; box-shadow: var(--yp-shadow-popover); }
</style>
