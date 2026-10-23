import { afterEach, describe, expect, it, vi } from 'vitest'
import { announceWorkItemChanged, onProjectWorkItemsChanged, onWorkItemChanged } from './workItemChanges'

afterEach(() => { vi.unstubAllGlobals() })

/** BroadcastChannel delivers on a later task. */
const delivered = () => new Promise(resolve => setTimeout(resolve, 20))

describe('工作项跨窗口变更', () => {
  it('其他窗口收到广播，取消后不再收到', async () => {
    const listener = vi.fn()
    const off = onWorkItemChanged(listener)
    announceWorkItemChanged({ projectId: 'p1', workItemId: 'w1' })
    await delivered()
    expect(listener).toHaveBeenCalledExactlyOnceWith({ projectId: 'p1', workItemId: 'w1' })
    off()
    announceWorkItemChanged({ projectId: 'p1' })
    await delivered()
    expect(listener).toHaveBeenCalledOnce()
  })

  it('没有 BroadcastChannel 时既不发送也不抛错', () => {
    vi.stubGlobal('BroadcastChannel', undefined)
    expect(() => announceWorkItemChanged({ projectId: 'p1' })).not.toThrow()
    const off = onWorkItemChanged(vi.fn())
    expect(() => off()).not.toThrow()
  })

  it('项目表格只为本项目的变更重载，并把连续变更合并为一次', async () => {
    const reload = vi.fn()
    let project = 'p1'
    const off = onProjectWorkItemsChanged(() => project, reload, 30)
    announceWorkItemChanged({ projectId: 'p2' })
    announceWorkItemChanged({ projectId: 'p1', workItemId: 'a' })
    announceWorkItemChanged({ projectId: 'p1', workItemId: 'b' })
    await new Promise(resolve => setTimeout(resolve, 80))
    expect(reload).toHaveBeenCalledOnce()
    project = 'p2'
    announceWorkItemChanged({ projectId: 'p1' })
    await new Promise(resolve => setTimeout(resolve, 80))
    expect(reload).toHaveBeenCalledOnce()
    announceWorkItemChanged({ projectId: 'p2' })
    await delivered()
    off()
    await new Promise(resolve => setTimeout(resolve, 60))
    expect(reload).toHaveBeenCalledOnce()
  })
})
