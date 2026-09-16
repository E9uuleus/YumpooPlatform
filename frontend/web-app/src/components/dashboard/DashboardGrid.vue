<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { GridStack, type GridStackWidget, type GridItemHTMLElement } from 'gridstack'
import 'gridstack/dist/gridstack.css'
import type { DashboardWidget } from '@yumpoo/api-client'
const props = defineProps<{ widgets: DashboardWidget[]; editing: boolean }>()
const emit = defineEmits<{ layout: [widgets: DashboardWidget[]]; move: [id: string, direction: number] }>()
const root = ref<HTMLElement>(), narrow = ref(false)
let grid: GridStack | undefined, observer: ResizeObserver | undefined, applying = false, size = 12
function nodes(): GridStackWidget[] { return props.widgets.map(w => ({ id: w.id, ...(size === 12 ? w.wide : size === 6 ? w.medium : { x: 0, y: props.widgets.indexOf(w) * 9, w: 1, h: w.kind === 'METRIC' ? 4 : 9 }), minW: size === 1 ? 1 : w.kind === 'METRIC' ? 3 : 4, minH: w.kind === 'METRIC' ? 4 : 7, maxH: 100 })) }
async function sync() {
  await nextTick(); if (!grid || !root.value) return
  applying = true; grid.batchUpdate()
  const ids = new Set(props.widgets.map(w => w.id))
  for (const node of [...grid.engine.nodes]) if (!ids.has(node.id!)) grid.removeWidget(node.el!, false, false)
  for (const node of nodes()) { const element = root.value.querySelector<GridItemHTMLElement>(`[gs-id="${node.id}"]`); if (element && !element.gridstackNode) grid.makeWidget(element, node) }
  grid.load(nodes(), false); grid.batchUpdate(false); grid.setStatic(!props.editing || narrow.value); applying = false
}
onMounted(() => {
  if (!root.value) return
  size = root.value.clientWidth < 600 ? 1 : root.value.clientWidth < 960 ? 6 : 12; narrow.value = size === 1
  grid = GridStack.init({ column: size, cellHeight: 40, margin: 8, float: false, animate: true, handle: '.dashboard-card__handle', resizable: { handles: 'se' }, draggable: { scroll: true }, staticGrid: !props.editing || narrow.value, auto: false }, root.value) || undefined
  grid?.on('dragstop resizestop', () => {
    // Collision updates settle after the pointer event; only gestures persist layout.
    setTimeout(() => {
      if (applying || !grid || size === 1) return
      const layout = grid.save(false) as GridStackWidget[]
      emit('layout', props.widgets.map(widget => { const n = layout.find(n => n.id === widget.id); return n ? { ...widget, [size === 12 ? 'wide' : 'medium']: { x: n.x || 0, y: n.y || 0, w: n.w || 3, h: n.h || 4 } } : widget }))
    }, 0)
  })
  void sync(); observer = new ResizeObserver(entries => { const width = entries[0]?.contentRect.width || 0; const next = width < 600 ? 1 : width < 960 ? 6 : 12; if (next !== size) { applying = true; size = next; narrow.value = next === 1; grid?.column(next, 'none'); void sync() } }); observer.observe(root.value)
})
watch(() => props.widgets, () => void sync(), { deep: true }); watch(() => props.editing, () => grid?.setStatic(!props.editing || narrow.value))
onBeforeUnmount(() => { observer?.disconnect(); grid?.destroy(false) })
</script>
<template>
  <div
    ref="root"
    class="grid-stack dashboard-grid"
    :class="{ 'is-editing': editing }"
  >
    <div
      v-for="widget in widgets"
      :key="widget.id"
      class="grid-stack-item"
      :gs-id="widget.id"
    >
      <div class="grid-stack-item-content">
        <slot
          :widget="widget"
          :narrow="narrow"
        />
      </div>
    </div>
  </div>
</template>
<style>
.dashboard-grid{margin:-8px;min-height:160px}.dashboard-grid .grid-stack-item-content{overflow:hidden!important;border:1px solid var(--yp-border-default);border-radius:8px;background:var(--yp-bg-surface);box-shadow:0 2px 5px #00000003}.dashboard-grid.is-editing .grid-stack-item-content{box-shadow:0 3px 12px #00000009}.dashboard-grid .grid-stack-placeholder>.placeholder-content{border:1px dashed var(--yp-action-primary);background:color-mix(in srgb,var(--yp-action-primary) 7%,transparent);border-radius:8px}.dashboard-grid .ui-resizable-se{opacity:.55}.dashboard-grid .ui-draggable-dragging{z-index:20}.dashboard-grid.is-editing .dashboard-card__handle{cursor:grab}
</style>
