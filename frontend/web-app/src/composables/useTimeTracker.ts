import { computed, ref, shallowRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { readCsrfToken, type CurrentTimeTracker, type TimeTrackingSummary, type TimeTrackingCommand } from '@yumpoo/api-client'
import { timeTrackingApi } from '../api/client'

const current = shallowRef<CurrentTimeTracker>()
const now = ref(Date.now())
const busy = ref(false)
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

export function formatDuration(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(seconds / 3600)}:${String(Math.floor(seconds / 60) % 60).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

export function useTimeTracker() {
  return { current, now, busy, refresh, toggle, stop, notify, summaries,
    runningDuration: computed(() => current.value?.session ? now.value - current.value.session.startedAt.getTime() : 0) }
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
  if (!active) {
    current.value = undefined
    summaries.value = {}
    retries.clear()
    return
  }
  channel = typeof BroadcastChannel === 'undefined' ? undefined : new BroadcastChannel('yumpoo-time-tracker')
  const invalidate = () => { void refresh().catch(() => undefined); listeners.forEach(listener => listener()) }
  if (channel) channel.onmessage = invalidate
  const tick = window.setInterval(() => { now.value = Date.now() + offset }, 1000)
  const poll = window.setInterval(() => { if (document.visibilityState === 'visible') void refresh().catch(() => undefined) }, 5000)
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
    const result = await timeTrackingApi.getCurrentTimeTracker()
    if (epoch !== generation) return
    if (!current.value || result.rowVersion >= current.value.rowVersion) current.value = result
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
  if (!current.value || result.rowVersion >= current.value.rowVersion) current.value = result
  await refresh()
}

async function toggle(workItemId: string, confirmSwitch?: () => Promise<boolean>): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    await refresh()
    const running = current.value?.session
    if (running?.workItemId === workItemId) await command('stop', { sessionId: running.id })
    else if (running) {
      if (confirmSwitch) { if (!await confirmSwitch()) return }
      else await ElMessageBox.confirm(`正在计时「${current.value?.workItemTitle ?? '不可见工作项'}」，停止并切换到此工作项？`, '切换计时', { confirmButtonText: '停止并切换', cancelButtonText: '取消' })
      await command('switch', { workItemId, sessionId: running.id })
    } else await command('start', { workItemId })
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error('计时操作未完成，请核对状态后重试。')
  } finally { busy.value = false }
}

async function stop(): Promise<void> {
  await refresh()
  if (current.value?.session) await command('stop', { sessionId: current.value.session.id })
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
    try { await stop(); return true }
    catch { ElMessage.error('停止失败，尚未退出。请重试或明确选择继续计时并退出。'); return false }
  })()
  exitPromise = operation
  void operation.finally(() => { exitPromise = undefined })
  return operation
}
