import { getCurrentInstance, nextTick, onScopeDispose, readonly, ref } from 'vue'
import type { Router } from 'vue-router'
import { shellSurfacePaths } from '../components/desktop/surfaces'

export function createPageProgress() {
  const visible = ref(false), value = ref(0)
  let generation = 0, path = '', navigating = false, enabled = true, booting = false
  let shownAt = 0, startedAt = 0, accepting = false
  let settled: ReturnType<typeof setTimeout> | undefined
  const tasks = new Set<symbol>()
  let reveal: ReturnType<typeof setTimeout> | undefined
  let finish: ReturnType<typeof setTimeout> | undefined
  let tick: ReturnType<typeof setInterval> | undefined
  const busy = () => navigating || tasks.size > 0
  function clearTimers() { clearTimeout(settled); clearTimeout(reveal); clearTimeout(finish); clearInterval(tick) }
  function show() {
    if (!enabled || booting || !busy()) return
    if (!visible.value) { visible.value = true; shownAt = Date.now(); value.value = .1 }
    tick = setInterval(() => { value.value += (.9 - value.value) * .08 }, 200)
  }
  function schedule() {
    clearTimers()
    if (busy()) {
      if (visible.value) { value.value = Math.min(value.value, .9); show() }
      else reveal = setTimeout(show, Math.max(0, 150 - (Date.now() - startedAt)))
    } else {
      settled = setTimeout(() => { accepting = false }, 0)
      if (!visible.value) return
      finish = setTimeout(() => {
        value.value = 1
        finish = setTimeout(() => { visible.value = false; value.value = 0 }, 180)
      }, Math.max(0, 200 - (Date.now() - shownAt)))
    }
  }
  function begin(destination: string) {
    generation++; accepting = true; startedAt = Date.now(); path = destination.split('#')[0]!; navigating = true; tasks.clear(); schedule()
    return generation
  }
  async function commit(id: number) {
    await nextTick()
    if (id !== generation) return
    navigating = false; schedule()
  }
  function cancel(id: number) {
    if (id !== generation) return
    navigating = false; tasks.clear(); schedule()
  }
  function acquire(destination: string): () => void {
    if (!enabled || !accepting || destination.split('#')[0] !== path) return () => undefined
    const id = generation, token = Symbol()
    tasks.add(token); schedule()
    return () => {
      if (id !== generation || !tasks.delete(token)) return
      schedule()
    }
  }
  function configure(allowed: boolean, splash: boolean) {
    enabled = allowed; booting = splash
    if (!allowed || splash) { clearTimers(); visible.value = false }
    else schedule()
  }
  function dispose() { generation++; accepting = false; clearTimers(); tasks.clear(); navigating = false; visible.value = false; value.value = 0 }
  return { visible: readonly(visible), value: readonly(value), begin, commit, cancel, acquire, configure, dispose }
}

export const pageProgress = createPageProgress()

export function installPageProgress(router: Router): void {
  const navigationIds = new WeakMap<object, number>()
  router.beforeEach((to, from) => {
    if (from.matched.length && to.path === from.path && JSON.stringify(to.query) === JSON.stringify(from.query)) return
    navigationIds.set(to, pageProgress.begin(to.fullPath))
  })
  router.afterEach((to, _from, failure) => {
    const id = navigationIds.get(to)
    if (id === undefined) return
    if (failure) pageProgress.cancel(id)
    else void pageProgress.commit(id)
  })
  router.onError((_error, to) => {
    const id = navigationIds.get(to)
    if (id !== undefined) pageProgress.cancel(id)
  })
}

export function configurePageProgress(splash = Boolean(document.getElementById('boot-splash'))): void {
  pageProgress.configure(!(window.yumpooDesktop && shellSurfacePaths.includes(window.location.pathname)), splash)
}

export function usePageLoad() {
  const instance = getCurrentInstance()
  const releases = new Set<() => void>()
  let disposed = false
  onScopeDispose(() => { disposed = true; for (const release of releases) release(); releases.clear() })
  async function track<T>(task: () => Promise<T>): Promise<T> {
    if (disposed) return task()
    const release = pageProgress.acquire(instance?.proxy?.$route?.fullPath ?? '')
    releases.add(release)
    try { return await task() }
    finally { await nextTick(); release(); releases.delete(release) }
  }
  return { track }
}
