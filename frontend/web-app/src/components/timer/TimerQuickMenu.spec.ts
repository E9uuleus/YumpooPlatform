import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TimerMenuState } from '@yumpoo/preload-contract'
import TimerQuickMenu from './TimerQuickMenu.vue'

const now = Date.parse('2026-09-24T08:00:00Z')
const base: TimerMenuState = { signedIn: true, ready: true, enabled: true, running: null, recent: { title: '最近工作' }, clockOffsetMs: 0, savedAt: 0, visible: true, display: 'orb', pinned: true }
let push!: (state: TimerMenuState) => void
const runMenuAction = vi.fn(async () => undefined)
const wrappers: Array<ReturnType<typeof mount>> = []
async function menu(state: TimerMenuState) {
  const wrapper = mount(TimerQuickMenu, { attachTo: document.body })
  wrappers.push(wrapper)
  push(state); await flushPromises()
  return wrapper
}
const option = (wrapper: ReturnType<typeof mount>, label: string) => wrapper.findAll('[role="menuitemradio"]').find(item => item.text() === label)!

beforeEach(() => {
  vi.useFakeTimers(); vi.setSystemTime(now); vi.clearAllMocks()
  vi.stubGlobal('yumpooDesktop', { timer: { runMenuAction, onMenuState: (listener: typeof push) => { push = listener; return vi.fn() } } })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.useRealTimers(); vi.unstubAllGlobals() })

describe('desktop quick panel', () => {
  it('opens inbox and changes its independent preference only when the shell supports it', async () => {
    const setPreferences = vi.fn(async () => ({ toasts: false }))
    vi.stubGlobal('yumpooDesktop', { inbox: { getPreferences: async () => ({ toasts: true }), setPreferences }, timer: { runMenuAction, onMenuState: (listener: typeof push) => { push = listener; return vi.fn() } } })
    const wrapper = await menu({ ...base, inbox: { unreadCount: 120 } })
    expect(wrapper.get('.inbox-count').text()).toBe('99+')
    await wrapper.get('.menu-inbox [role="menuitem"]').trigger('click')
    expect(runMenuAction).toHaveBeenCalledWith('open-inbox')
    await wrapper.get('[aria-label="桌面通知"]').trigger('click')
    await flushPromises()
    expect(setPreferences).toHaveBeenCalledWith({ toasts: false })
    expect(wrapper.get('[aria-label="桌面通知"]').attributes('aria-checked')).toBe('false')
  })

  it('hides inbox controls for old shells', async () => {
    const wrapper = await menu(base)
    expect(wrapper.find('.menu-inbox').exists()).toBe(false)
    expect(wrapper.find('[aria-label="桌面通知"]').exists()).toBe(false)
  })
  it('ticks the running duration with the server clock offset and pauses through the shell', async () => {
    const wrapper = await menu({ ...base, running: { title: '需求评审', startedAt: new Date(now - 65_000).toISOString() }, clockOffsetMs: 5000 })
    expect(wrapper.get('.status-copy strong').text()).toBe('需求评审')
    expect(wrapper.get('.status-sub').text()).toBe('0:01:10')
    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.get('.status-sub').text()).toBe('0:01:12')
    await wrapper.get('[aria-label="暂停计时"]').trigger('click')
    expect(runMenuAction).toHaveBeenCalledWith('toggle')
  })

  it('reflects the display mode and pin state and routes every choice to the shell', async () => {
    const wrapper = await menu(base)
    expect(option(wrapper, '悬浮球').attributes('aria-checked')).toBe('true')
    expect(wrapper.get('[role="menuitemcheckbox"]').attributes('aria-checked')).toBe('true')
    await option(wrapper, '侧边栏').trigger('click')
    await option(wrapper, '隐藏').trigger('click')
    await wrapper.get('[role="menuitemcheckbox"]').trigger('click')
    await wrapper.get('[aria-label="计时设置"]').trigger('click')
    await wrapper.findAll('[role="menuitem"]').find(item => item.text() === '查找工作项…')!.trigger('click')
    await wrapper.findAll('[role="menuitem"]').find(item => item.text().includes('退出'))!.trigger('click')
    expect(runMenuAction.mock.calls.map(call => (call as unknown[])[0])).toEqual(['show-dock', 'hide', 'toggle-pin', 'settings', 'find', 'exit'])
    push({ ...base, visible: false, pinned: false }); await flushPromises()
    expect(option(wrapper, '隐藏').attributes('aria-checked')).toBe('true')
    expect(wrapper.get('[role="menuitemcheckbox"]').attributes('aria-checked')).toBe('false')
  })

  it('moves focus with the arrow keys and closes on Escape', async () => {
    const wrapper = await menu(base)
    await vi.advanceTimersByTimeAsync(1)
    expect(document.activeElement).toBe(wrapper.get('[role="menu"]').element)
    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'ArrowUp' })
    expect((document.activeElement as HTMLElement).textContent).toContain('退出')
    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'Home' })
    expect(document.activeElement).toBe(wrapper.get('[aria-label="计时设置"]').element)
    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(wrapper.get('[aria-label="继续计时"]').element)
    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'End' })
    expect((document.activeElement as HTMLElement).textContent).toContain('退出')
    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(wrapper.get('[aria-label="计时设置"]').element)
    await wrapper.get('[role="menu"]').trigger('keydown', { key: 'Escape' })
    expect(runMenuAction).toHaveBeenLastCalledWith('close')
  })

  it('keeps only safe entries available while signed out or disconnected', async () => {
    const wrapper = await menu({ ...base, signedIn: false, ready: false, enabled: false, recent: null })
    expect(wrapper.get('.status-copy strong').text()).toBe('尚未登录')
    expect(wrapper.get('.menu-connection').text()).toBe('未登录')
    expect(wrapper.find('.status-toggle').exists()).toBe(false)
    expect(wrapper.get('[aria-label="计时设置"]').attributes('disabled')).toBeDefined()
    expect(option(wrapper, '悬浮球').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('[role="menuitem"]').find(item => item.text() === '打开主界面')!.attributes('disabled')).toBeUndefined()
    push({ ...base, ready: false, enabled: false }); await flushPromises()
    expect(wrapper.get('.status-sub').text()).toBe('正在等待连接…')
    expect(wrapper.get('[aria-label="继续计时"]').attributes('disabled')).toBeDefined()
    push({ ...base, savedAt: now }); await flushPromises()
    expect(wrapper.get('.status-sub').text()).toBe('已保存本次计时')
    await vi.advanceTimersByTimeAsync(3000)
    expect(wrapper.get('.status-sub').text()).toBe('已暂停 · 可继续上次工作')
  })
})
