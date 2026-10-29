import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { useOperationsQuery } from '../composables/useOperationsQuery'
import { createMemoryHistory, createRouter } from 'vue-router'
import { configurePageProgress, createPageProgress, installPageProgress, pageProgress, usePageLoad } from './page-progress'

describe('页面加载协调', () => {
  let progress: ReturnType<typeof createPageProgress>
  beforeEach(() => { vi.useFakeTimers(); progress = createPageProgress() })
  afterEach(() => { progress.dispose(); pageProgress.dispose(); vi.unstubAllGlobals(); vi.useRealTimers() })

  it('快速完成不闪烁，慢加载至少显示 200ms', async () => {
    await progress.commit(progress.begin('/fast'))
    await vi.advanceTimersByTimeAsync(500)
    expect(progress.visible.value).toBe(false)
    const id = progress.begin('/slow')
    await vi.advanceTimersByTimeAsync(149)
    expect(progress.visible.value).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(progress.visible.value).toBe(true)
    await progress.commit(id)
    await vi.advanceTimersByTimeAsync(199)
    expect(progress.value.value).toBeLessThan(1)
    await vi.advanceTimersByTimeAsync(181)
    expect(progress.visible.value).toBe(false)
  })

  it('路由完成后等待所有任务，串行任务交接不会提前结束', async () => {
    const id = progress.begin('/data'), a = progress.acquire('/data'), b = progress.acquire('/data')
    await progress.commit(id)
    await vi.advanceTimersByTimeAsync(500)
    a(); b()
    const c = progress.acquire('/data')
    await vi.advanceTimersByTimeAsync(1000)
    expect(progress.visible.value).toBe(true)
    expect(progress.value.value).toBeLessThanOrEqual(.9)
    c(); c()
    await vi.advanceTimersByTimeAsync(500)
    expect(progress.visible.value).toBe(false)
    progress.acquire('/data')
    await vi.advanceTimersByTimeAsync(500)
    expect(progress.visible.value).toBe(false)
  })

  it('旧导航和请求不能终止新导航', async () => {
    const old = progress.begin('/old'), release = progress.acquire('/old')
    const current = progress.begin('/new')
    release(); progress.cancel(old); await progress.commit(old)
    await vi.advanceTimersByTimeAsync(500)
    expect(progress.visible.value).toBe(true)
    progress.cancel(current)
    await vi.advanceTimersByTimeAsync(500)
    expect(progress.visible.value).toBe(false)
  })

  it('启动画面交接与禁用窗口', async () => {
    progress.configure(true, true)
    const id = progress.begin('/boot'), release = progress.acquire('/boot')
    await progress.commit(id)
    await vi.advanceTimersByTimeAsync(1000)
    expect(progress.visible.value).toBe(false)
    progress.configure(true, false)
    await vi.advanceTimersByTimeAsync(1)
    expect(progress.visible.value).toBe(true)
    release()
    progress.configure(false, false)
    progress.begin('/timer')
    await vi.advanceTimersByTimeAsync(1000)
    expect(progress.visible.value).toBe(false)
  })

  it('真实路由挂载后的数据延迟、拒绝和卸载都能收尾', async () => {
    let reject!: (reason: Error) => void
    const Page = defineComponent({ setup() {
      const { track } = usePageLoad()
      void track(() => new Promise<void>((_resolve, fail) => { reject = fail })).catch(() => undefined)
      return () => 'page'
    } })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: Page }, { path: '/other', component: Page }] })
    installPageProgress(router)
    const wrapper = mount({ template: '<router-view :key="$route.path" />' }, { global: { plugins: [router] } })
    await router.isReady(); await nextTick()
    await vi.advanceTimersByTimeAsync(200)
    expect(pageProgress.visible.value).toBe(true)
    reject(new Error('offline'))
    await flushPromises(); await vi.advanceTimersByTimeAsync(500)
    expect(pageProgress.visible.value).toBe(false)
    await router.push('/other'); await nextTick()
    await vi.advanceTimersByTimeAsync(200)
    expect(pageProgress.visible.value).toBe(true)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(500)
    expect(pageProgress.visible.value).toBe(false)
  })
  it('主窗口启用，Electron 四个壳专用路径禁用', async () => {
    vi.stubGlobal('yumpooDesktop', { client: 'electron' })
    for (const path of ['/timer', '/timer/menu', '/timer/quick', '/timer/rail']) {
      window.history.replaceState({}, '', path)
      configurePageProgress(false)
      pageProgress.begin(path)
      await vi.advanceTimersByTimeAsync(200)
      expect(pageProgress.visible.value).toBe(false)
    }
    window.history.replaceState({}, '', '/docs')
    configurePageProgress(false)
    pageProgress.begin('/docs')
    await vi.advanceTimersByTimeAsync(200)
    expect(pageProgress.visible.value).toBe(true)
  })

  it('首次运维查询参与等待，后续轮询不重新点亮', async () => {
    let resolve!: (value: string) => void
    const load = vi.fn(() => new Promise<string>(done => { resolve = done }))
    const Page = defineComponent({ setup() {
      useOperationsQuery(load, { pageLoad: true, interval: () => 1000 })
      return () => 'operations'
    } })
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: Page }] })
    installPageProgress(router)
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [router] } })
    await router.isReady(); await nextTick()
    await vi.advanceTimersByTimeAsync(200)
    expect(pageProgress.visible.value).toBe(true)
    resolve('ready'); await flushPromises()
    await vi.advanceTimersByTimeAsync(1500)
    expect(load).toHaveBeenCalledTimes(2)
    expect(pageProgress.visible.value).toBe(false)
    wrapper.unmount()
  })

  it('守卫重定向延续加载，懒加载失败不会残留，锚点不触发', async () => {
    let resolve!: () => void
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: { render: () => null } },
      { path: '/redirect', component: { render: () => null } },
      { path: '/slow', component: { render: () => null } },
      { path: '/broken', component: () => Promise.reject(new Error('chunk unavailable')) },
    ] })
    installPageProgress(router)
    router.onError(() => undefined)
    router.beforeEach(to => {
      if (to.path === '/redirect') return '/slow'
      if (to.path === '/slow') return new Promise<void>(done => { resolve = done })
    })
    await router.push('/')
    await vi.advanceTimersByTimeAsync(500)
    await router.push('/#section')
    await vi.advanceTimersByTimeAsync(200)
    expect(pageProgress.visible.value).toBe(false)
    const navigation = router.push('/redirect')
    await flushPromises(); await vi.advanceTimersByTimeAsync(200)
    expect(pageProgress.visible.value).toBe(true)
    resolve(); await navigation
    await vi.advanceTimersByTimeAsync(500)
    expect(pageProgress.visible.value).toBe(false)
    await expect(router.push('/broken')).rejects.toThrow('chunk unavailable')
    await vi.advanceTimersByTimeAsync(500)
    expect(pageProgress.visible.value).toBe(false)
  })

  it('卸载后的串行续作不登记到新页面，也不阻塞新页面收尾', async () => {
    let resume!: () => void, finishNew!: () => void
    const oldWork = new Promise<void>(resolve => { resume = resolve })
    const newWork = new Promise<void>(resolve => { finishNew = resolve })
    const OldPage = defineComponent({ setup() {
      const { track } = usePageLoad()
      void (async () => {
        await track(() => oldWork)
        await track(() => new Promise<void>(() => undefined))
      })()
      return () => 'old'
    } })
    const NewPage = defineComponent({ setup() {
      void usePageLoad().track(() => newWork)
      return () => 'new'
    } })
    const router = createRouter({ history: createMemoryHistory(), routes: [
      { path: '/', component: OldPage }, { path: '/new', component: NewPage },
    ] })
    installPageProgress(router)
    const wrapper = mount({ template: '<router-view />' }, { global: { plugins: [router] } })
    try {
      await router.isReady(); await nextTick()
      await router.push('/new'); await nextTick()
      await vi.advanceTimersByTimeAsync(200)
      resume(); await flushPromises()
      finishNew(); await flushPromises()
      await vi.advanceTimersByTimeAsync(500)
      expect(pageProgress.visible.value).toBe(false)
    } finally { wrapper.unmount() }
  })

})
