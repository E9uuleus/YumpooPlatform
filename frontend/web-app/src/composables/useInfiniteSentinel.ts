import { nextTick, onBeforeUnmount, watch, type Ref, type WatchSource } from 'vue'

const MARGIN = 160

/**
 * Loads the next page whenever the sentinel at the end of a list comes within 160px of its scroll container,
 * and again after each page that leaves it there, so a short first page keeps filling the list.
 */
export function useInfiniteSentinel(root: Ref<HTMLElement | undefined>, sentinel: Ref<HTMLElement | undefined>,
  size: WatchSource<number>, canLoad: () => boolean, more: () => Promise<unknown>) {
  let observer: IntersectionObserver | undefined
  let pending = false
  const near = () => {
    const list = root.value, end = sentinel.value
    return !!list && !!end && end.getBoundingClientRect().top <= list.getBoundingClientRect().bottom + MARGIN
  }
  async function check() {
    if (pending || !near() || !canLoad()) return
    pending = true
    try { await more() } finally { pending = false }
  }
  watch([root, sentinel], ([list, end]) => {
    observer?.disconnect()
    observer = undefined
    if (!list || !end || typeof IntersectionObserver === 'undefined') return
    observer = new IntersectionObserver(entries => { if (entries.some(entry => entry.isIntersecting)) void check() },
      { root: list, rootMargin: `0px 0px ${MARGIN}px 0px` })
    observer.observe(end)
  }, { immediate: true, flush: 'post' })
  watch(size, () => { void nextTick(check) }, { flush: 'post' })
  onBeforeUnmount(() => observer?.disconnect())
}
