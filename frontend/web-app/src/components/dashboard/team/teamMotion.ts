import { onScopeDispose, ref, watch, type Ref } from 'vue'

const EASE = 'cubic-bezier(0.2, 0.8, 0.2, 1)'
const reducedMotion = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches

/** Follows `source`, rolling from the previous value to the new one with an ease-out curve. */
export function useTweenedNumber(source: () => number, duration = 520): Ref<number> {
  const value = ref(source())
  let frame = 0
  const animates = () => typeof requestAnimationFrame === 'function' && !reducedMotion()
  watch(source, target => {
    if (!animates()) { value.value = target; return }
    cancelAnimationFrame(frame)
    const from = value.value, start = performance.now()
    const step = (now: number) => {
      const progress = Math.min(1, (now - start) / duration)
      value.value = from + (target - from) * (1 - (1 - progress) ** 3)
      if (progress < 1) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
  })
  onScopeDispose(() => { if (frame) cancelAnimationFrame(frame) })
  return value
}

export type StaggerFrom = 'up' | 'left' | 'right'

/** Fades in the elements that are inside `viewport`, one after another, sliding from `from`. Only the first `limit` visible ones move. */
export function staggerIn(elements: Iterable<Element>, viewport: Element, from: StaggerFrom = 'up', limit = 16) {
  if (reducedMotion()) return
  const bounds = viewport.getBoundingClientRect(), visible: Element[] = []
  for (const element of elements) {
    const box = element.getBoundingClientRect()
    if (box.top >= bounds.bottom || visible.length === limit) break
    if (box.bottom > bounds.top) visible.push(element)
  }
  const offset = from === 'up' ? 'translateY(8px)' : `translateX(${from === 'left' ? -18 : 18}px)`
  visible.forEach((element, index) => element.animate?.([{ opacity: 0, transform: offset }, { opacity: 1, transform: 'none' }],
    { duration: 300, delay: index * 22, easing: EASE, fill: 'backwards' }))
}
