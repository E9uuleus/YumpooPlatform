import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { DesktopUpdateBridge, DesktopUpdateState } from '@yumpoo/preload-contract'
import DesktopUpdateBanner from './DesktopUpdateBanner.vue'

const ready: DesktopUpdateState = { phase: 'ready', currentVersion: '1.0.0', channel: 'stable', version: '1.0.1' }
const mounted: Array<ReturnType<typeof mount>> = []

function bridge(initial: DesktopUpdateState) {
  let listener: ((state: DesktopUpdateState) => void) | undefined
  const update: DesktopUpdateBridge & { emit: (state: DesktopUpdateState) => void } = {
    getState: vi.fn(async () => initial),
    check: vi.fn(async () => undefined),
    restart: vi.fn(async () => undefined),
    onState: vi.fn(next => { listener = next; return vi.fn() }),
    emit: state => listener?.(state),
  }
  vi.stubGlobal('yumpooDesktop', { client: 'electron', update })
  return update
}

async function render() {
  const wrapper = mount(DesktopUpdateBanner)
  mounted.push(wrapper)
  await flushPromises()
  return wrapper
}

afterEach(() => {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.unstubAllGlobals()
})

describe('desktop update banner', () => {
  it('stays hidden in browsers and older shells without the update bridge', async () => {
    vi.stubGlobal('yumpooDesktop', { client: 'electron' })
    expect((await render()).text()).toBe('')
    vi.stubGlobal('yumpooDesktop', undefined)
    expect((await render()).text()).toBe('')
  })

  it('appears only once a verified update is ready and restarts through the bridge', async () => {
    const update = bridge({ phase: 'downloading', currentVersion: '1.0.0', channel: 'stable', version: '1.0.1', percent: 20 })
    const wrapper = await render()
    expect(wrapper.text()).toBe('')
    update.emit(ready)
    await flushPromises()
    expect(wrapper.text()).toContain('Yumpoo Desktop 1.0.1 已下载并通过校验')
    await wrapper.findAll('button').find(button => button.text() === '重启以更新')!.trigger('click')
    expect(update.restart).toHaveBeenCalledOnce()
  })

  it('can be postponed until a newer version is ready', async () => {
    const update = bridge(ready)
    const wrapper = await render()
    await wrapper.findAll('button').find(button => button.text() === '稍后')!.trigger('click')
    expect(wrapper.text()).toBe('')
    update.emit({ ...ready, version: '1.0.2' })
    await flushPromises()
    expect(wrapper.text()).toContain('1.0.2')
  })

  it('reports a failed restart without hiding the banner', async () => {
    const update = bridge(ready)
    vi.mocked(update.restart).mockRejectedValueOnce(new Error('UPDATE_NOT_READY'))
    const wrapper = await render()
    await wrapper.findAll('button').find(button => button.text() === '重启以更新')!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('无法开始更新')
  })
})
