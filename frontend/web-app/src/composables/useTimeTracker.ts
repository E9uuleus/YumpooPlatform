import { computed, ref, shallowRef } from 'vue'
import { ElMessage } from 'element-plus'
import { readCsrfToken, type CurrentTimeTracker, type TimeTrackingSummary, type TimeTrackingCommand } from '@yumpoo/api-client'
import { timeTrackingApi } from '../api/client'

const current = shallowRef<CurrentTimeTracker>()
const now = ref(Date.now())
const busy = ref(false)
const problem = ref('')
const connected = ref(false)
const savedAt = ref(0)
const summaries = ref<Record<string, { value: TimeTrackingSummary; at: number }>>({})
const observed = new Map<string, { projectId: string; count: number }>()
let enabled = false
let generation = 0
let offset = 0
let refreshPromise: Promise<void> | undefined
let channel: BroadcastChannel | undefined
let cleanup: (() => void) | undefined
const retries = new Map<string, string>()
const listeners = new Set<() => void>()

export function formatTrackingDuration(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(seconds / 3600)
  return `${hours ? `${hours}h ` : ''}${Math.floor(seconds / 60) % 60}m ${seconds % 60}s`
}

export function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

export function useTimeTracker() {
  return { current, now, busy, problem, connected, savedAt, refresh, toggle, start, stop, notify, summaries,
    runningDuration: computed(() => current.value?.session ? Math.max(0, now.value - current.value.session.startedAt.getTime()) : 0) }
}

export function observeTimer(workItemId: string, projectId: string, initial?: TimeTrackingSummary | null): () => void {
  const entry = observed.get(workItemId)
  observed.set(workItemId, { projectId, count: (entry?.count ?? 0) + 1 })
  if (initial && !summaries.value[workItemId]) summaries.value[workItemId] = { value: initial, at: now.value }
  return () => {
    const entry = observed.get(workItemId)
    if (entry && --entry.count === 0) observed.delete(workItemId)
  }
}

export function onTimeTrackingChanged(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function activateTimeTracker(active: boolean): void {
  cleanup?.()
  cleanup = undefined
  generation++
  enabled = active
  refreshPromise = undefined
  current.value = undefined
  summaries.value = {}
  retries.clear()
  busy.value = false
  problem.value = ''
  connected.value = false
  savedAt.value = 0
  offset = 0
  if (!active) {
    return
  }
  channel = typeof BroadcastChannel === 'undefined' ? undefined : new BroadcastChannel('yumpoo-time-tracker')
  const invalidate = () => {
    const pending = refreshPromise
    if (pending) void pending.catch(() => undefined).then(() => refresh()).catch(() => undefined)
    else void refresh().catch(() => undefined)
    listeners.forEach(listener => listener())
  }
  if (channel) channel.onmessage = invalidate
  const tick = window.setInterval(() => { now.value = Date.now() + offset }, 1000)
  const poll = window.setInterval(() => { if (window.yumpooDesktop || document.visibilityState === 'visible') void refresh().catch(() => undefined) }, 5000)
  const focus = () => { if (document.visibilityState === 'visible') void refresh().catch(() => undefined) }
  window.addEventListener('focus', focus)
  window.addEventListener('online', focus)
  document.addEventListener('visibilitychange', focus)
  const offDesktop = window.yumpooDesktop?.timer?.onRefresh(invalidate)
  cleanup = () => {
    window.clearInterval(tick); window.clearInterval(poll); channel?.close(); channel = undefined
    window.removeEventListener('focus', focus); window.removeEventListener('online', focus)
    document.removeEventListener('visibilitychange', focus); offDesktop?.()
  }
  void refresh().catch(() => undefined)
}

async function refresh(): Promise<void> {
  if (!enabled) return
  if (refreshPromise) return refreshPromise
  const epoch = generation
  const operation = (async () => {
    let result: CurrentTimeTracker
    try { result = await timeTrackingApi.getCurrentTimeTracker() }
    catch (error) { if (epoch === generation) connected.value = false; throw error }
    if (epoch !== generation) return
    connected.value = true
    acceptCurrent(result)
    offset = result.serverNow.getTime() - Date.now()
    now.value = Date.now() + offset
    const projects = new Map<string, string[]>()
    observed.forEach((entry, id) => projects.set(entry.projectId, [...(projects.get(entry.projectId) ?? []), id]))
    await Promise.allSettled([...projects].map(async ([projectId, ids]) => {
      for (let i = 0; i < ids.length; i += 100) {
        const page = await timeTrackingApi.getTimeTrackingSummaries({ projectId, workItemId: ids.slice(i, i + 100) })
        if (epoch !== generation) return
        page.items.forEach(value => { summaries.value[value.workItemId] = { value, at: page.serverNow.getTime() } })
      }
    }))
  })()
  refreshPromise = operation
  try { await operation } finally { if (refreshPromise === operation) refreshPromise = undefined }
}

function acceptCurrent(result: CurrentTimeTracker): void {
  const previous = current.value
  if (previous && result.rowVersion < previous.rowVersion) return
  if (result.session) savedAt.value = 0
  else if (previous?.session && result.rowVersion > previous.rowVersion) savedAt.value = Date.now()
  current.value = result
}

function notify(): void {
  channel?.postMessage({ changed: true })
  void window.yumpooDesktop?.timer?.refresh()
  listeners.forEach(listener => listener())
  const pending = refreshPromise
  if (pending) void pending.catch(() => undefined).then(() => refresh()).catch(() => undefined)
  else void refresh().catch(() => undefined)
}

export async function timerMutation<T>(identity: string, call: (key: string, csrf: string) => Promise<T>): Promise<T> {
  const key = retries.get(identity) ?? crypto.randomUUID()
  retries.set(identity, key)
  const csrf = readCsrfToken()
  if (!csrf) throw new Error('请刷新页面恢复登录凭据')
  try {
    const result = await call(key, csrf)
    retries.delete(identity)
    notify()
    return result
  } catch (error) {
    const status = (error as { response?: Response }).response?.status
    if (status && status < 500) retries.delete(identity)
    if (status === 409 || status === 412) {
      await refresh().catch(() => undefined)
      ElMessage.warning('计时状态或记录已变化，请核对后重新操作。')
    }
    throw error
  }
}

async function command(action: 'start' | 'switch' | 'stop', body: TimeTrackingCommand): Promise<void> {
  const epoch = generation
  const snapshot = current.value
  if (!snapshot) throw new Error('计时状态未加载，请重试')
  const identity = JSON.stringify([action, snapshot.etag, body])
  const method = action === 'start' ? 'startTimeTracker' : action === 'switch' ? 'switchTimeTracker' : 'stopTimeTracker'
  const result = await timerMutation(identity, (idempotencyKey, xXSRFTOKEN) => timeTrackingApi[method]({
    ifMatch: snapshot.etag, idempotencyKey, xXSRFTOKEN, timeTrackingCommand: body,
  }))
  if (epoch !== generation) return
  acceptCurrent(result)
  // The command response is authoritative even if the following calibration fails.
  void refresh().catch(() => undefined)
}

async function run(action: () => Promise<void>): Promise<boolean> {
  if (busy.value || !enabled) return false
  const epoch = generation
  busy.value = true
  problem.value = ''
  try {
    await refresh()
    if (epoch !== generation) return false
    await action()
    return epoch === generation
  } catch (error) {
    if (epoch === generation) {
      const status = (error as { response?: Response }).response?.status
      problem.value = status === 409 || status === 412 || (error as Error).message === 'TIMER_CHANGED'
        ? '计时已在其他窗口变化，已刷新，请重新选择。'
        : '操作尚未确认，请检查连接后重试。已有计时会继续保留。'
      ElMessage.error(problem.value)
    }
    return false
  } finally { if (epoch === generation) busy.value = false }
}

async function start(workItemId: string): Promise<boolean> {
  const success = await run(async () => {
    const running = current.value?.session
    if (running?.workItemId === workItemId) return
    await command(running ? 'switch' : 'start', { workItemId, ...(running ? { sessionId: running.id } : {}) })
  })
  if (success) window.dispatchEvent(new Event('yumpoo:timer-started'))
  return success
}

function toggle(workItemId: string): Promise<boolean> {
  const running = current.value?.session
  return running?.workItemId === workItemId ? stop(running.id) : start(workItemId)
}

function stop(expectedSessionId = current.value?.session?.id): Promise<boolean> {
  return run(async () => {
    const running = current.value?.session
    if (!running) return
    if (!expectedSessionId || running.id !== expectedSessionId) throw new Error('TIMER_CHANGED')
    await command('stop', { sessionId: running.id })
  })
}

export const timerExitPrompt = ref(false)
let resolveExit: ((choice: 'stop' | 'continue' | 'cancel') => void) | undefined
let exitPromise: Promise<boolean> | undefined
export function chooseTimerExit(choice: 'stop' | 'continue' | 'cancel'): void {
  timerExitPrompt.value = false
  resolveExit?.(choice)
  resolveExit = undefined
}
export function confirmTimerExit(): Promise<boolean> {
  if (exitPromise) return exitPromise
  const operation = (async () => {
    let confirmed = false
    try { await refresh(); confirmed = true } catch { /* Keep explicit exit choices available while offline. */ }
    if (confirmed && !current.value?.session) return true
    timerExitPrompt.value = true
    const choice = await new Promise<'stop' | 'continue' | 'cancel'>(resolve => { resolveExit = resolve })
    if (choice === 'cancel') return false
    if (choice === 'continue') return true
    if (await stop()) return true
    ElMessage.error('停止失败，尚未退出。请重试或明确选择继续计时并退出。')
    return false
  })()
  exitPromise = operation
  void operation.finally(() => { exitPromise = undefined })
  return operation
}
