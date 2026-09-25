interface FlipOptions {
  measure: () => Map<string, number>
  targets: () => Array<{ key: string; element: HTMLElement }>
  disabled: () => boolean
  requestFrame?: (callback: FrameRequestCallback) => number
  cancelFrame?: (id: number) => void
}

export function createWorkItemColumnFlip(options: FlipOptions) {
  const requestFrame = options.requestFrame ?? requestAnimationFrame
  const cancelFrame = options.cancelFrame ?? cancelAnimationFrame
  let revision = 0
  let frame: number | undefined
  const animations = new Set<Animation>()
  function cancel(): void {
    revision++
    if (frame !== undefined) cancelFrame(frame)
    frame = undefined
    animations.forEach(animation => animation.cancel())
    animations.clear()
  }
  function capture() {
    cancel()
    return options.disabled() ? undefined : { revision, positions: options.measure() }
  }
  function play(snapshot: ReturnType<typeof capture>): void {
    if (!snapshot || snapshot.revision !== revision || options.disabled()) return
    frame = requestFrame(() => {
      frame = undefined
      if (snapshot.revision !== revision || options.disabled()) return
      const positions = options.measure()
      for (const { key, element } of options.targets()) {
        if (!positions.has(key) || !element.animate) continue
        const previous = snapshot.positions.get(key)
        const delta = previous === undefined ? 0 : previous - positions.get(key)!
        if (previous !== undefined && Math.abs(delta) < 0.5) continue
        const animation = element.animate(previous === undefined
          ? [{ opacity: 0 }, { opacity: 1 }]
          : [{ transform: `translateX(${delta}px)` }, { transform: 'translateX(0px)' }],
        { duration: 160, easing: 'ease-out' })
        animations.add(animation)
        animation.onfinish = () => animations.delete(animation)
      }
    })
  }
  return { capture, play, cancel }
}

function columnKey(cell: Element): string | undefined {
  if (cell.classList.contains('monday-title-column')) return 'title'
  return [...cell.classList].find(name => /^monday-column(?:-header)?--/.test(name))?.replace(/^monday-column(?:-header)?--/, '')
}

export function measureWorkItemColumns(root?: HTMLElement): Map<string, number> {
  const row = root?.querySelector('.el-table__header-wrapper tr')
    ?? root?.querySelector('.work-item-group-columns:not(.work-item-group-collapsed)')
  const positions = new Map<string, number>()
  for (const cell of row?.children ?? []) {
    const key = columnKey(cell)
    if (key) positions.set(key, cell.getBoundingClientRect().left)
  }
  return positions
}

export function visibleWorkItemColumnCells(root?: HTMLElement): Array<{ key: string; element: HTMLElement }> {
  if (!root) return []
  const viewport = root.getBoundingClientRect()
  const top = Math.max(0, viewport.top), bottom = Math.min(window.innerHeight, viewport.bottom)
  const left = Math.max(0, viewport.left), right = Math.min(window.innerWidth, viewport.right)
  const targets: Array<{ key: string; element: HTMLElement }> = []
  for (const row of root.querySelectorAll('tr')) {
    const rect = row.getBoundingClientRect()
    if (!rect.height || rect.bottom <= top || rect.top >= bottom) continue
    for (const cell of row.children) {
      const key = columnKey(cell)
      if (!key || !(cell instanceof HTMLElement)) continue
      const cellRect = cell.getBoundingClientRect()
      if (cellRect.right > left && cellRect.left < right) targets.push({ key, element: cell })
    }
  }
  return targets
}
