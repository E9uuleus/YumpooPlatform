import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useInfiniteSentinel } from './useInfiniteSentinel'

let intersect: (entries: { isIntersecting: boolean }[]) => void
const observe = vi.fn(), disconnect = vi.fn()
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: typeof intersect) { intersect = callback }
    observe = observe
    disconnect = disconnect
  })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })
function setup(top = 250, allowed = true, more = vi.fn(async () => {})) {
  const root = ref(document.createElement('div')), sentinel = ref(document.createElement('div')), size = ref(0)
  vi.spyOn(root.value, 'getBoundingClientRect').mockReturnValue({ bottom: 100 } as DOMRect)
  vi.spyOn(sentinel.value, 'getBoundingClientRect').mockReturnValue({ top } as DOMRect)
  wrappers.push(mount(defineComponent({ setup() {
    useInfiniteSentinel(root, sentinel, size, () => allowed, more)
    return () => null
  } })))
  return { size, more }
}
const settle = async () => { await nextTick(); await flushPromises() }

describe('候选列表自动分段加载', () => {
  it('160px 内尺寸变化继续填充，卸载后断开观察', async () => {
    const { size, more } = setup(260)
    size.value++
    await settle()
    expect(more).toHaveBeenCalledOnce()
    size.value++
    await settle()
    expect(more).toHaveBeenCalledTimes(2)
    expect(observe).toHaveBeenCalledOnce()
    wrappers.pop()!.unmount()
    expect(disconnect).toHaveBeenCalledOnce()
  })
  it('范围外不加载', async () => {
    const { size, more } = setup(261)
    size.value++
    await settle()
    expect(more).not.toHaveBeenCalled()
  })
  it('不可加载时不请求', async () => {
    const { size, more } = setup(200, false)
    size.value++
    intersect([{ isIntersecting: true }])
    await settle()
    expect(more).not.toHaveBeenCalled()
  })
  it('上一次请求未结束时不会重复加载', async () => {
    let resolve!: () => void
    const more = vi.fn(() => new Promise<void>(done => { resolve = done }))
    const { size } = setup(200, true, more)
    intersect([{ isIntersecting: true }])
    size.value++
    await settle()
    intersect([{ isIntersecting: true }])
    expect(more).toHaveBeenCalledOnce()
    resolve()
    await settle()
    intersect([{ isIntersecting: true }])
    expect(more).toHaveBeenCalledTimes(2)
    resolve()
  })
  it('相交回调触发加载，未相交时不触发', async () => {
    const { more } = setup()
    intersect([{ isIntersecting: false }])
    expect(more).not.toHaveBeenCalled()
    intersect([{ isIntersecting: true }])
    await settle()
    expect(more).toHaveBeenCalledOnce()
  })
})
