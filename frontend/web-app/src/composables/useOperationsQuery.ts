import { onBeforeUnmount, onMounted, ref, shallowRef, watch, type WatchStopHandle } from 'vue'
import { toApiProblem, type ApiProblem } from '../api/problems'
import { ensureAuthentication } from './useSession'

export function useOperationsQuery<T>(
  load: (signal: AbortSignal) => Promise<T>,
  options: { interval?: () => number; enabled?: () => boolean; immediate?: boolean } = {},
) {
  const data = shallowRef<T>(),
    error = shallowRef<ApiProblem>(),
    updatedAt = ref<Date>()
  const loading = ref(false)
  let controller: AbortController | undefined, timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0,
    disposed = false,
    mounted = false,
    blocked = false,
    unwatch: WatchStopHandle | undefined
  const allowed = () =>
    !disposed && !blocked && (options.enabled?.() ?? true) && document.visibilityState !== 'hidden'
  function clear() {
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  function cancel() {
    generation++
    controller?.abort()
    controller = undefined
    loading.value = false
    clear()
  }
  function schedule() {
    clear()
    const interval = options.interval?.() ?? 0
    if (mounted && allowed() && interval > 0)
      timer = setTimeout(() => {
        const focused = document.activeElement
        if (focused?.matches('input,textarea,select,[contenteditable="true"]')) schedule()
        else void refresh()
      }, interval)
  }
  async function refresh(): Promise<void> {
    if (!allowed()) return
    cancel()
    const request = generation
    controller = new AbortController()
    loading.value = true
    error.value = undefined
    try {
      const result = await load(controller.signal)
      if (!disposed && generation === request) {
        data.value = result
        updatedAt.value = new Date()
      }
    } catch (reason) {
      if (generation === request && !controller?.signal.aborted) {
        const problem = await toApiProblem(reason)
        if (!disposed && generation === request) {
          error.value = problem
          if (problem.kind === 'response' && [401, 403].includes(problem.status)) {
            blocked = true
            data.value = undefined
            updatedAt.value = undefined
            void ensureAuthentication(true)
          }
        }
      }
    } finally {
      if (!disposed && generation === request) {
        controller = undefined
        loading.value = false
        schedule()
      }
    }
  }
  function visibility() {
    if (!allowed()) {
      cancel()
      return
    }
    const editing = document.activeElement?.matches(
      'input,textarea,select,[contenteditable="true"]',
    )
    if (
      options.immediate !== false &&
      !editing &&
      (data.value === undefined || (options.interval?.() ?? 0) > 0)
    )
      void refresh()
    else schedule()
  }
  onMounted(() => {
    mounted = true
    document.addEventListener('visibilitychange', visibility)
    unwatch = watch(
      () => [options.enabled?.() ?? true, options.interval?.() ?? 0],
      ([enabled], previous) => {
        if (!enabled) {
          cancel()
          data.value = undefined
          error.value = undefined
        } else if (previous?.[0] === false) {
          blocked = false
          void refresh()
        } else schedule()
      },
    )
    if (options.immediate !== false) void refresh()
  })
  onBeforeUnmount(() => {
    disposed = true
    cancel()
    unwatch?.()
    document.removeEventListener('visibilitychange', visibility)
  })
  return { data, error, loading, updatedAt, refresh, cancel }
}
