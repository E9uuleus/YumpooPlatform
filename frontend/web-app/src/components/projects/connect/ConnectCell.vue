<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElPopover } from 'element-plus'
import type { ConnectColumn, ConnectColumnIncoming, WorkItemConnection } from '@yumpoo/api-client'
import '../workItemAccentBar.css'
import type { ConnectSourceItem } from './connectColumnKeys'
import ConnectionChip from './ConnectionChip.vue'
import ConnectItemPicker from './ConnectItemPicker.vue'

/** A connect cell: forward cells show target cards, reverse cells (two-way columns in a target project) show source cards. */
const props = defineProps<{ item: ConnectSourceItem; column?: ConnectColumn | undefined; reverse?: ConnectColumnIncoming | undefined
  connections: WorkItemConnection[]; total?: number | undefined; readOnly: boolean }>()
const emit = defineEmits<{ openCard: [connection: WorkItemConnection]; changed: [] }>()
const open = ref(false), root = ref<HTMLElement>(), chips = ref<HTMLElement>(), measure = ref<HTMLElement>(), opener = ref<HTMLButtonElement>()
const visibleCount = ref(0)
const showProject = computed(() => Boolean(props.column && props.column.targets.length > 1))
const total = computed(() => props.total ?? props.connections.length)
const canAdd = computed(() => !props.readOnly && (Boolean(props.column) || Boolean(props.reverse?.actorCanLinkExisting)))
const canOpenPopover = computed(() => total.value > 0 || canAdd.value)
const hiddenCount = computed(() => Math.max(0, total.value - visibleCount.value))
const label = computed(() => props.column?.name ?? props.reverse?.projectName ?? '连接')
const card = (connection: WorkItemConnection) => props.column ? connection.target : connection.source
let observer: ResizeObserver | undefined, lastWidth = -1
function measureChips(force = false) {
  const width = chips.value?.clientWidth ?? 0
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
  // Like Monday, a narrow cell still names the first item and lets its chip truncate.
  visibleCount.value = Math.max(count, widths.length ? 1 : 0)
}
onMounted(() => {
  observer = new ResizeObserver(() => measureChips())
  if (chips.value) observer.observe(chips.value)
  void nextTick(() => measureChips(true))
})
watch(() => [props.connections.map(connection => `${connection.id}:${card(connection).title}:${card(connection).projectName}`).join('|'), showProject.value, total.value],
  () => { void nextTick(() => measureChips(true)) })
watch(() => props.item.id, () => { open.value = false })
watch(canOpenPopover, allowed => { if (!allowed) open.value = false })
onBeforeUnmount(() => observer?.disconnect())
function close() { open.value = false; void nextTick(() => opener.value?.focus({ preventScroll: true })) }
function openCard(connection: WorkItemConnection) { open.value = false; emit('openCard', connection) }
</script>

<template>
  <el-popover
    v-model:visible="open"
    trigger="click"
    placement="bottom-start"
    :width="400"
    :disabled="!canOpenPopover"
    popper-class="connect-cell-popper"
  >
    <template #reference>
      <div
        ref="root"
        class="connect-cell"
        :class="{ 'connect-cell--writable': canAdd, 'connect-cell--open': open, 'connect-cell--empty': !total }"
        @click.stop
        @keydown.esc.stop.prevent="close"
      >
        <div class="connect-cell__box work-item-accent-bar">
          <button
            v-if="canOpenPopover"
            ref="opener"
            type="button"
            class="connect-cell__open"
            :aria-label="`${label}：${item.title}，${total} 个连接`"
            :aria-expanded="open"
            @click.stop="open = !open"
          />
          <span
            v-if="!total"
            class="connect-cell__empty"
            aria-hidden="true"
          >–</span>
          <div
            ref="chips"
            class="connect-cell__chips"
          >
            <transition-group name="connect-chip">
              <connection-chip
                v-for="connection in connections.slice(0, visibleCount)"
                :key="connection.id"
                :card="card(connection)"
                :show-project="showProject"
                @click="openCard(connection)"
              />
            </transition-group>
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
          <span
            v-if="canAdd"
            class="connect-cell__plus"
            aria-hidden="true"
          >+</span>
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
    <connect-item-picker
      v-if="open"
      :item="item"
      :column="column"
      :reverse="reverse"
      :connections="connections"
      :total="total"
      :read-only="readOnly"
      @open-card="openCard"
      @changed="emit('changed')"
      @close="close"
    />
  </el-popover>
</template>

<style scoped>
.connect-cell { position: relative; display: flex; align-items: center; width: 100%; min-width: 0; height: 34px; }
.connect-cell__box { display: flex; align-items: center; width: 100%; min-width: 0; height: var(--work-item-quick-control-height, 26px); margin: 0 2px; padding: 0 24px 0 12px; box-sizing: border-box; background: var(--yp-bg-sunken); transition: background-color var(--yp-motion-fast) var(--yp-ease-standard), box-shadow var(--yp-motion-fast) var(--yp-ease-standard); }
.connect-cell--writable:hover .connect-cell__box { background: var(--yp-bg-hover); }
.connect-cell--open .connect-cell__box { background: var(--yp-bg-selected); box-shadow: 0 0 0 1px var(--yp-input-border-focus) inset; }
.connect-cell__open { position: absolute; z-index: 0; inset: 0; width: 100%; padding: 0; border: 0; border-radius: inherit; background: transparent; cursor: pointer; }
.connect-cell__open:focus-visible { outline: none; box-shadow: 0 0 0 1px var(--yp-input-border-focus) inset; }
.connect-cell__empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--yp-text-muted); pointer-events: none; }
.connect-cell__chips { position: relative; z-index: 1; display: flex; flex: 1; gap: 4px; min-width: 0; align-items: center; pointer-events: none; }
.connect-cell__chips :deep(button) { pointer-events: auto; }
.connect-cell__chips :deep(.connection-chip) { flex-shrink: 1; min-width: 48px; }
.connect-cell__count { display: inline-flex; flex-shrink: 0; height: 20px; align-items: center; padding: 0 6px; border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-xs); background: var(--yp-bg-surface); color: var(--yp-text-secondary); font-size: 11px; cursor: pointer; }
.connect-cell__count:hover { background: var(--yp-bg-hover); }
.connect-cell__plus { position: absolute; z-index: 1; top: 50%; right: 4px; display: grid; width: 16px; height: 16px; place-items: center; border-radius: 50%; background: var(--yp-action-primary); color: var(--yp-status-blue-foreground); font-size: 13px; line-height: 1; opacity: 0; transform: translateY(-50%) scale(.8); transition: opacity var(--yp-motion-fast) var(--yp-ease-standard), transform var(--yp-motion-fast) var(--yp-ease-standard); pointer-events: none; }
.connect-cell--writable:hover .connect-cell__plus, .connect-cell__open:focus-visible ~ .connect-cell__plus, .connect-cell--open .connect-cell__plus { opacity: 1; transform: translateY(-50%) scale(1); }
.connect-cell__measure { position: absolute; display: flex; width: max-content; height: 0; overflow: hidden; visibility: hidden; pointer-events: none; }
.connect-chip-enter-active, .connect-chip-leave-active { transition: opacity var(--yp-motion-popover) var(--yp-ease-standard), transform var(--yp-motion-popover) var(--yp-ease-standard); }
.connect-chip-enter-from { opacity: 0; transform: scale(.88); }
.connect-chip-leave-to { opacity: 0; transform: scale(.92); }
@media (prefers-reduced-motion: reduce) {
  .connect-cell__box, .connect-cell__plus, .connect-chip-enter-active, .connect-chip-leave-active { transition: none; }
}
</style>

<style>
.connect-cell-popper.el-popover.el-popper { max-width: calc(100vw - 32px); padding: 14px; box-sizing: border-box; border-radius: var(--yp-radius-lg); box-shadow: var(--yp-shadow-popover); }
</style>
