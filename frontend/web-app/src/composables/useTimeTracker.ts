import { computed, ref, shallowRef } from 'vue'
import type { DesktopExitPhase } from '@yumpoo/preload-contract'
import { ElMessage } from 'element-plus'
import { readCsrfToken, type CurrentTimeTracker, type TimeTrackingPreferences, type TimeTrackingSummary, type TimeTrackingCommand } from '@yumpoo/api-client'
import { timeTrackingApi } from '../api/client'
import { timeProblemMessage } from './timeTrackingProblems'
import { SHELL_SURFACE } from '../components/desktop/surfaces'
import { categoryOf, categoryProjectOf, sessionKey, subjectFields, type SubjectKey } from '../components/timer/timeCategories'

const current = shallowRef<CurrentTimeTracker>()
const running = computed(() => current.value?.runningSessions ?? [])
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
export const DEFAULT_TIME_PREFERENCES: TimeTrackingPreferences = { timerEnabled: true, workdayStartMinute: 540, workdayEndMinute: 1080, hideCompleted: false }
const PREFERENCES_KEY = 'yumpoo.time-tracking.preferences.v1'
/** Last known preferences, so timer controls do not flash in before the first poll for someone who switched the timer off. */
const cachedPreferences = shallowRef<TimeTrackingPreferences | undefined>(readCachedPreferences())
const preferences = computed(() => current.value?.preferences ?? cachedPreferences.value ?? DEFAULT_TIME_PREFERENCES)
const timerEnabled = computed(() => preferences.value.timerEnabled)

function readCachedPreferences(): TimeTrackingPreferences | undefined {
  try {
    const value = JSON.parse(localStorage.getItem(PREFERENCES_KEY) ?? 'null') as TimeTrackingPreferences | null
    return value && typeof value.timerEnabled === 'boolean' ? value : undefined
  } catch { return undefined }
}
function cachePreferences(value: TimeTrackingPreferences | undefined): void {
  cachedPreferences.value = value
  try {
    if (value) localStorage.setItem(PREFERENCES_KEY, JSON.stringify(value))
    else localStorage.removeItem(PREFERENCES_KEY)
  } catch { /* Storage may be unavailable; the server value still applies after the first poll. */ }
}

export function formatTrackingDuration(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(seconds / 3600)
  return `${hours ? `${hours}h ` : ''}${Math.floor(seconds / 60) % 60}m ${seconds % 60}s`
}

export { formatDuration } from '../components/timer/duration'

/** Server clock minus local clock from the latest poll, published so other desktop surfaces tick the same duration. */
export function timerClockOffset(): number { return offset }

/** Shared like the rest of the state, so callers inside computed getters do not allocate one per evaluation. */
const wallDuration = computed(() => running.value.length ? Math.max(0, now.value - Math.min(...running.value.map(timer => timer.session.startedAt.getTime()))) : 0)

export function useTimeTracker() {
  return { current, running, isRunning, runningFor, now, busy, problem, connected, savedAt, refresh, toggle, start, startMany, switchTo, stop, stopAll, notify, summaries,
    preferences, timerEnabled, setPreferences, wallDuration }
}

/** Timer commands take a work item id or a category key. */
function runningFor(key: SubjectKey) { return running.value.find(timer => sessionKey(timer.session) === key) }
function isRunning(key: SubjectKey): boolean { return !!runningFor(key) }

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
    cachePreferences(undefined)
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
  // Desktop windows keep polling while hidden in the tray, except the prewarmed quick panel: it refreshes when shown (visibilitychange below).
  const pollHidden = !!window.yumpooDesktop && window.location.pathname !== SHELL_SURFACE.quick
  const poll = window.setInterval(() => { if (pollHidden || document.visibilityState === 'visible') void refresh().catch(() => undefined) }, 5000)
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
  if (result.runningSessions.length) savedAt.value = 0
  else if (previous?.runningSessions.length && result.rowVersion > previous.rowVersion) savedAt.value = Date.now()
  current.value = result
  if (result.preferences) cachePreferences(result.preferences)
}

function notify(): void {
  channel?.postMessage({ changed: true })
  void window.yumpooDesktop?.timer?.refresh()
  listeners.forEach(listener => listener())
  const pending = refreshPromise
  if (pending) void pending.catch(() => undefined).then(() => refresh()).catch(() => undefined)
  else void refresh().catch(() => undefined)
}

/** `quiet` leaves conflict messages to the caller, which can explain them in context. */
export async function timerMutation<T>(identity: string, call: (key: string, csrf: string) => Promise<T>, options: { quiet?: boolean } = {}): Promise<T> {
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
      if (!options.quiet) ElMessage.warning('计时状态或记录已变化，请核对后重新操作。')
    }
    throw error
  }
}

async function command(action: 'start' | 'switch' | 'stop' | 'stop-all', body: TimeTrackingCommand = {}): Promise<void> {
  const epoch = generation
  const snapshot = current.value
  if (!snapshot) throw new Error('计时状态未加载，请重试')
  const identity = JSON.stringify([action, snapshot.etag, body])
  const method = action === 'start' ? 'startTimeTracker' : action === 'switch' ? 'switchTimeTracker' : 'stopTimeTracker'
  const result = await timerMutation(identity, (idempotencyKey, xXSRFTOKEN) => {
    const headers = { ifMatch: snapshot.etag, idempotencyKey, xXSRFTOKEN }
    return action === 'stop-all' ? timeTrackingApi.stopAllTimeTrackers(headers) : timeTrackingApi[method]({ ...headers, timeTrackingCommand: body })
  })
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
      const message = status === 409 ? await timeProblemMessage(error) : ''
      problem.value = message.startsWith('计时器已关闭') ? message
        : status === 409 || status === 412 || (error as Error).message === 'TIMER_CHANGED'
        ? '计时已在其他窗口变化，已刷新，请重新选择。'
        : '操作尚未确认，请检查连接后重试。已有计时会继续保留。'
      ElMessage.error(problem.value)
    }
    return false
  } finally { if (epoch === generation) busy.value = false }
}

async function start(key: SubjectKey): Promise<boolean> {
  if (isRunning(key)) return true
  if (!timerEnabled.value) return false
  const success = await run(async () => {
    if (!isRunning(key)) await command('start', subjectFields(key))
  })
  if (success) window.dispatchEvent(new Event('yumpoo:timer-started'))
  return success
}

async function startMany(keys: SubjectKey[]): Promise<boolean> {
  if (!timerEnabled.value) return false
  const success = await run(async () => {
    for (let index = 0; index < keys.length; index += 10) {
      const batch = keys.slice(index, index + 10)
      const workItemIds = batch.filter(key => !categoryOf(key))
      const timeCategorySubjects = batch.flatMap(key => {
        const category = categoryOf(key)
        return category ? [{ timeCategory: category.code, projectId: categoryProjectOf(key) }] : []
      })
      await command('start', { ...(workItemIds.length ? { workItemIds } : {}), ...(timeCategorySubjects.length ? { timeCategorySubjects } : {}) })
    }
  })
  if (success) window.dispatchEvent(new Event('yumpoo:timer-started'))
  return success
}

async function switchTo(key: SubjectKey): Promise<boolean> {
  if (!timerEnabled.value) return false
  const success = await run(() => command('switch', subjectFields(key)))
  if (success) window.dispatchEvent(new Event('yumpoo:timer-started'))
  return success
}

function toggle(key: SubjectKey): Promise<boolean> {
  const timer = runningFor(key)
  return timer ? stop(timer.session.id) : start(key)
}

function stop(sessionId: string): Promise<boolean> {
  return run(async () => {
    if (!running.value.some(timer => timer.session.id === sessionId)) throw new Error('TIMER_CHANGED')
    await command('stop', { sessionId })
  })
}

function stopAll(): Promise<boolean> { return run(() => command('stop-all')) }

/** Saving with the timer off also stops every running timer on the server, in the same transaction. */
function setPreferences(next: TimeTrackingPreferences): Promise<boolean> {
  return run(async () => {
    const snapshot = current.value
    if (!snapshot) throw new Error('计时状态未加载，请重试')
    const result = await timerMutation(JSON.stringify(['preferences', snapshot.etag, next]), (idempotencyKey, xXSRFTOKEN) =>
      timeTrackingApi.updateTimeTrackingPreferences({ ifMatch: snapshot.etag, idempotencyKey, xXSRFTOKEN, timeTrackingPreferences: next }))
    acceptCurrent(result)
  })
}

export const timerExitPrompt = ref(false)
export const timerExitPhase = ref<DesktopExitPhase>('checking')
let resolveExit: ((choice: 'stop' | 'continue' | 'cancel') => void) | undefined
let exitPromise: Promise<boolean> | undefined
let cancelExit: (() => void) | undefined
let exitGeneration = 0
export function chooseTimerExit(choice: 'stop' | 'continue' | 'cancel'): void {
  timerExitPrompt.value = false
  resolveExit?.(choice)
  resolveExit = undefined
}
export function cancelTimerExit(): void {
  chooseTimerExit('cancel')
  cancelExit?.()
}
export function confirmTimerExit(): Promise<boolean> {
  if (exitPromise) return exitPromise
  const token = ++exitGeneration
  timerExitPhase.value = 'checking'
  const cancelled = new Promise<boolean>(resolve => { cancelExit = () => { ++exitGeneration; resolve(false) } })
  const operation = (async () => {
    let confirmed = false
    try { await refresh(); confirmed = true } catch { /* Keep explicit exit choices available while offline. */ }
    if (token !== exitGeneration) return false
    if (confirmed && !running.value.length) return true
    timerExitPhase.value = 'prompt'
    timerExitPrompt.value = true
    const choice = await new Promise<'stop' | 'continue' | 'cancel'>(resolve => { resolveExit = resolve })
    if (token !== exitGeneration || choice === 'cancel') return false
    if (choice === 'continue') return true
    timerExitPhase.value = 'saving'
    const stopped = await run(async () => {
      // A cancelled exit must not submit a stop after a delayed pre-save refresh.
      if (token === exitGeneration) await command('stop-all')
    })
    if (token !== exitGeneration) return false
    if (stopped) return true
    ElMessage.error('停止失败，尚未退出。请重试或明确选择继续计时并退出。')
    return false
  })()
  const result = Promise.race([operation, cancelled]).finally(() => {
    if (exitPromise === result) { exitPromise = undefined; cancelExit = undefined }
  })
  exitPromise = result
  return result
}
