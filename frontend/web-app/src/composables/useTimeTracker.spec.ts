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
})
