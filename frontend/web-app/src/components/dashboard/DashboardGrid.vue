<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { GridStack, type GridStackWidget, type GridItemHTMLElement } from 'gridstack'
import 'gridstack/dist/gridstack.css'
import type { DashboardWidget } from '@yumpoo/api-client'
import { resolveChart } from './chartModel'
import { settleDashboardLayout } from './dashboardLayout'
import { DashboardGridEngine } from './dashboardGridEngine'
const props = defineProps<{ widgets: DashboardWidget[] }>()
const emit = defineEmits<{ layout: [widgets: DashboardWidget[]]; ready: [] }>()
const root = ref<HTMLElement>(), narrow = ref(false)
let grid: GridStack | undefined, observer: ResizeObserver | undefined, applying = false, size = 12, sequence = 0, revealSequence = 0, observedWidth = 0
function nodes(): GridStackWidget[] {
  let stackedY = 0
  return settleDashboardLayout(props.widgets).map(w => {
    const cellWidth = (root.value?.clientWidth || 960) / size
    const minW = size === 1 ? 1 : Math.max(1, Math.ceil(144 / cellWidth))
    const h = Math.max(3, w.wide.h)
    const position = size === 12 ? w.wide : size === 6 ? w.medium : { x: 0, y: stackedY, w: 1 }
    stackedY += h
    return { id: w.id, ...position, h, minW, minH: 3, maxH: 100 }
  })
}
async function sync() {
  const token = ++sequence
  await nextTick(); if (token !== sequence || !grid || !root.value) return
  applying = true; grid.batchUpdate()
  const ids = new Set(props.widgets.map(w => w.id))
  for (const node of [...grid.engine.nodes]) if (!ids.has(node.id!)) grid.removeWidget(node.el!, false, false)
  for (const node of nodes()) { const element = root.value.querySelector<GridItemHTMLElement>(`[gs-id="${node.id}"]`); if (element && !element.gridstackNode) grid.makeWidget(element, node) }
  grid.load(nodes(), false); grid.batchUpdate(false); grid.setStatic(narrow.value); applying = false; emit('ready')
}
async function reveal(id: string) {
  const token = ++revealSequence
  await sync(); await new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve())))
  await Promise.all((root.value?.getAnimations?.({ subtree: true }) || []).map(animation => animation.finished.catch(() => undefined)))
  if (token !== revealSequence) return
  const target = root.value?.querySelector<HTMLElement>(`[gs-id="${id}"]`)
  target?.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'center' })
  target?.animate?.([{ outline: '2px solid var(--yp-action-primary)' }, { outline: '2px solid transparent' }], { duration: 1500 })
}
defineExpose({ reveal })
onMounted(() => {
  if (!root.value) return
  size = root.value.clientWidth < 600 ? 1 : root.value.clientWidth < 960 ? 6 : 12; narrow.value = size === 1
  grid = GridStack.init({ engineClass: DashboardGridEngine, column: size, cellHeight: 40, margin: 8, float: true, animate: true, handle: '.dashboard-card__handle', resizable: { handles: 'se' }, draggable: { scroll: true }, staticGrid: narrow.value, auto: false }, root.value) || undefined
  grid?.on('dragstop resizestop', (event: Event) => {
    const columns = size, generation = sequence, instance = grid
    // Collision updates settle after the pointer event; only gestures persist layout.
    setTimeout(() => {
      if (applying || !grid || grid !== instance || size === 1 || size !== columns || sequence !== generation) return
      const layout = grid.save(false, false, undefined, columns) as GridStackWidget[]
      emit('layout', settleDashboardLayout(props.widgets.map(widget => {
        const n = layout.find(n => n.id === widget.id)
        if (!n) return widget
        const h = n.h ?? n.minH ?? 3
        return { ...widget, wide: { ...widget.wide, h }, medium: { ...widget.medium, h },
          [columns === 12 ? 'wide' : 'medium']: { x: n.x ?? 0, y: n.y ?? 0, w: n.w ?? n.minW ?? 1, h } }
      }), event.type === 'resizestop'))
    }, 0)
  })
  void sync(); observer = new ResizeObserver(entries => { const width = entries[0]?.contentRect.width || 0; if (Math.abs(width - observedWidth) < 1) return; observedWidth = width; const next = width < 600 ? 1 : width < 960 ? 6 : 12; if (next !== size) { applying = true; size = next; narrow.value = next === 1; grid?.column(next, 'none') } void sync() }); observer.observe(root.value)
})
watch(() => props.widgets.map(w => [w.id, resolveChart(w).type, w.wide, w.medium]), () => void sync(), { deep: true })
onBeforeUnmount(() => { ++sequence; ++revealSequence; observer?.disconnect(); grid?.destroy(false); grid = undefined })
</script>
<template>
  <div
    ref="root"
    class="grid-stack dashboard-grid"
    :class="{ 'is-editable': !narrow }"
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
