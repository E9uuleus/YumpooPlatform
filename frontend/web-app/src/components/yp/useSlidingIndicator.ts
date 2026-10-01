import { nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'

/** Positions an absolutely placed indicator under the active item; transitions start only after the first measurement. */
export function useSlidingIndicator(
  container: Ref<HTMLElement | undefined>,
  itemSelector: string,
  activeIndex: () => number,
) {
  const indicatorStyle = ref<Record<string, string>>({ opacity: '0' })
  const animated = ref(false)
  let observer: ResizeObserver | undefined

  function measure(): void {
    const item = container.value?.querySelectorAll<HTMLElement>(itemSelector)[activeIndex()]
    indicatorStyle.value = item
      ? { width: `${item.offsetWidth}px`, transform: `translateX(${item.offsetLeft}px)` }
      : { opacity: '0' }
  }

  onMounted(() => {
    measure()
    requestAnimationFrame(() => { animated.value = true })
    void document.fonts?.ready.then(measure)
    if (typeof ResizeObserver === 'undefined' || !container.value) return
    observer = new ResizeObserver(measure)
    observer.observe(container.value)
    container.value.querySelectorAll<HTMLElement>(itemSelector).forEach(item => observer?.observe(item))
  })

  watch(activeIndex, () => { void nextTick(measure) })
  onBeforeUnmount(() => observer?.disconnect())

  return { indicatorStyle, animated, measure }
}
