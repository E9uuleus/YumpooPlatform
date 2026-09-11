import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ getCurrentTimeTracker: vi.fn(), getTimeTrackingSummaries: vi.fn(), startTimeTracker: vi.fn(), switchTimeTracker: vi.fn(), stopTimeTracker: vi.fn() }))
vi.mock('../api/client', () => ({ timeTrackingApi: api }))
vi.mock('@yumpoo/api-client', () => ({ readCsrfToken: () => 'csrf' }))
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn(), warning: vi.fn() }, ElMessageBox: { confirm: vi.fn(async () => undefined) } }))
import { activateTimeTracker, chooseTimerExit, confirmTimerExit, timerMutation, useTimeTracker } from './useTimeTracker'

function state(version: number, running = false) {
  return { rowVersion: version, etag: `"${version}"`, serverNow: new Date(), workItemTitle: running ? '测试事项' : null, recentItems: [],
    session: running ? { id: 'session', workItemId: 'item', projectId: 'project', startedAt: new Date(), userId: 'user' } : null }
}
beforeEach(() => { vi.clearAllMocks(); vi.stubGlobal('BroadcastChannel', undefined); api.getCurrentTimeTracker.mockResolvedValue(state(0)); activateTimeTracker(true) })
afterEach(() => { activateTimeTracker(false); vi.unstubAllGlobals() })

describe('application timer facts', () => {
  it('reuses the same idempotency key after a lost response', async () => {
    await useTimeTracker().refresh()
    const call = vi.fn().mockRejectedValueOnce(new Error('response lost')).mockResolvedValueOnce({ ok: true })
    await expect(timerMutation('same-command', call)).rejects.toThrow('response lost')
    await timerMutation('same-command', call)
    expect(call.mock.calls[0]?.[0]).toBe(call.mock.calls[1]?.[0])
  })
  it('does not replace an acknowledged running state with an older polling response', async () => {
    api.startTimeTracker.mockResolvedValue(state(1, true))
    await useTimeTracker().toggle('item')
    expect(useTimeTracker().current.value?.rowVersion).toBe(1)
    expect(useTimeTracker().current.value?.session?.id).toBe('session')
  })
  it('keeps the application open on stop failure and permits an explicit continue choice', async () => {
    await useTimeTracker().refresh()
    api.getCurrentTimeTracker.mockResolvedValue(state(1, true))
    api.stopTimeTracker.mockRejectedValue(new Error('offline'))
    const stoppedExit = confirmTimerExit(); await flushPromises(); chooseTimerExit('stop')
    expect(await stoppedExit).toBe(false)
    expect(useTimeTracker().current.value?.session?.id).toBe('session')
    const continuedExit = confirmTimerExit(); await flushPromises(); chooseTimerExit('continue')
    expect(await continuedExit).toBe(true)
    expect(api.stopTimeTracker).toHaveBeenCalledOnce()
  })
  it('switches in one command without stopping separately or confirming', async () => {
    api.getCurrentTimeTracker.mockResolvedValue(state(1, true))
    const switched = state(2, true)
    switched.session!.workItemId = 'next'
    api.switchTimeTracker.mockResolvedValue(switched)
    expect(await useTimeTracker().start('next')).toBe(true)
    expect(api.switchTimeTracker).toHaveBeenCalledWith(expect.objectContaining({
      ifMatch: '"1"', timeTrackingCommand: { workItemId: 'next', sessionId: 'session' },
    }))
    expect(api.stopTimeTracker).not.toHaveBeenCalled()
    expect(api.startTimeTracker).not.toHaveBeenCalled()
  })
  it('a retry after an acknowledged but lost start response never toggles the timer off', async () => {
    api.startTimeTracker.mockRejectedValueOnce(new Error('response lost'))
    expect(await useTimeTracker().start('item')).toBe(false)
    api.getCurrentTimeTracker.mockResolvedValue(state(1, true))
    expect(await useTimeTracker().start('item')).toBe(true)
    expect(api.startTimeTracker).toHaveBeenCalledOnce()
    expect(api.stopTimeTracker).not.toHaveBeenCalled()
    expect(api.switchTimeTracker).not.toHaveBeenCalled()
  })
  it('does not pause a replacement session selected by another window', async () => {
    api.getCurrentTimeTracker.mockResolvedValue(state(1, true))
    await useTimeTracker().refresh()
    const replacement = state(2, true)
    replacement.session!.id = 'replacement'
    api.getCurrentTimeTracker.mockResolvedValue(replacement)
    expect(await useTimeTracker().stop('session')).toBe(false)
    expect(api.stopTimeTracker).not.toHaveBeenCalled()
    expect(useTimeTracker().current.value?.session?.id).toBe('replacement')
  })
  it('shares the busy guard between pause, start and table toggles', async () => {
    api.getCurrentTimeTracker.mockResolvedValue(state(1, true))
    await useTimeTracker().refresh()
    let resolve!: (value: ReturnType<typeof state>) => void
    api.stopTimeTracker.mockImplementation(() => new Promise(done => { resolve = done }))
    const stopping = useTimeTracker().stop()
    await flushPromises()
    expect(await useTimeTracker().toggle('next')).toBe(false)
    expect(await useTimeTracker().stop()).toBe(false)
    resolve(state(2))
    expect(await stopping).toBe(true)
    expect(api.stopTimeTracker).toHaveBeenCalledOnce()
    expect(api.switchTimeTracker).not.toHaveBeenCalled()
  })
  it('retains command success when subsequent synchronization is offline', async () => {
    api.startTimeTracker.mockImplementation(async () => {
      api.getCurrentTimeTracker.mockRejectedValue(new Error('offline'))
      return state(1, true)
    })
    expect(await useTimeTracker().start('item')).toBe(true)
    expect(useTimeTracker().current.value?.session?.id).toBe('session')
    expect(useTimeTracker().connected.value).toBe(false)
  })
  it('discards late command results after logout', async () => {
    let resolve!: (value: ReturnType<typeof state>) => void
    api.startTimeTracker.mockImplementation(() => new Promise(done => { resolve = done }))
    const starting = useTimeTracker().start('item')
    await flushPromises()
    activateTimeTracker(false)
    resolve(state(1, true))
    expect(await starting).toBe(false)
    expect(useTimeTracker().current.value).toBeUndefined()
    expect(useTimeTracker().busy.value).toBe(false)
  })
  it('does not carry a previous account version into a new authenticated lifecycle', async () => {
    api.getCurrentTimeTracker.mockResolvedValue(state(10, true))
    await useTimeTracker().refresh()
    api.getCurrentTimeTracker.mockResolvedValue(state(0))
    activateTimeTracker(true)
    expect(useTimeTracker().current.value).toBeUndefined()
    await useTimeTracker().refresh()
    expect(useTimeTracker().current.value?.rowVersion).toBe(0)
    expect(useTimeTracker().current.value?.session).toBeNull()
  })
  it('confirms a saved record only after an accepted stop and retains feedback when polling fails', async () => {
    api.getCurrentTimeTracker.mockResolvedValue(state(1, true))
    await useTimeTracker().refresh()
    let resolve!: (value: ReturnType<typeof state>) => void
    api.stopTimeTracker.mockImplementation(() => new Promise(done => { resolve = done }))
    const stopping = useTimeTracker().stop(); await flushPromises()
    expect(useTimeTracker().savedAt.value).toBe(0)
    api.getCurrentTimeTracker.mockRejectedValue(new Error('offline'))
    resolve(state(2))
    expect(await stopping).toBe(true)
    expect(useTimeTracker().savedAt.value).toBeGreaterThan(0)
    expect(useTimeTracker().connected.value).toBe(false)
    activateTimeTracker(false)
    expect(useTimeTracker().savedAt.value).toBe(0)
  })
  it('never reports a failed stop or stale paused state as saved', async () => {
    api.getCurrentTimeTracker.mockResolvedValue(state(4, true))
    await useTimeTracker().refresh()
    api.stopTimeTracker.mockRejectedValue(new Error('offline'))
    expect(await useTimeTracker().stop()).toBe(false)
    expect(useTimeTracker().savedAt.value).toBe(0)
    api.getCurrentTimeTracker.mockResolvedValue(state(3))
    await useTimeTracker().refresh()
    expect(useTimeTracker().savedAt.value).toBe(0)
    expect(useTimeTracker().current.value?.session).not.toBeNull()
  })
  it('releases the command and shows the saved acknowledgement while background calibration is pending', async () => {
    api.getCurrentTimeTracker.mockResolvedValue(state(1, true))
    await useTimeTracker().refresh()
    api.stopTimeTracker.mockImplementation(async () => {
      api.getCurrentTimeTracker.mockImplementation(() => new Promise(() => undefined))
      return state(2)
    })
    let completed = false
    void useTimeTracker().stop().then(success => { completed = success })
    await flushPromises()
    expect(completed).toBe(true)
    expect(useTimeTracker().busy.value).toBe(false)
    expect(useTimeTracker().savedAt.value).toBeGreaterThan(0)
  })
  it('reports a stop synchronized from another window once without replaying the feedback on polls', async () => {
    api.getCurrentTimeTracker.mockResolvedValue(state(1, true))
    await useTimeTracker().refresh()
    api.getCurrentTimeTracker.mockResolvedValue(state(2))
    await useTimeTracker().refresh()
    const savedAt = useTimeTracker().savedAt.value
    expect(savedAt).toBeGreaterThan(0)
    await useTimeTracker().refresh()
    expect(useTimeTracker().savedAt.value).toBe(savedAt)
  })
})
