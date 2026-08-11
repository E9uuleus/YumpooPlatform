import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { routes } from './router'

afterEach(() => {
  Reflect.deleteProperty(window, 'yumpooDesktop')
})

async function mountApplication() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes,
  })
  await router.push('/')
  await router.isReady()
  return mount(App, {
    global: {
      plugins: [router],
    },
  })
}

describe('Yumpoo Web 壳', () => {
  it('在浏览器模式挂载占位首页', async () => {
    const wrapper = await mountApplication()
    expect(wrapper.text()).toContain('Vue SPA 已就绪')
    expect(wrapper.text()).toContain('Web 浏览器')
  })

  it('识别最小桌面 bridge', async () => {
    Object.defineProperty(window, 'yumpooDesktop', {
      configurable: true,
      value: Object.freeze({ client: 'electron' }),
    })
    const wrapper = await mountApplication()
    expect(wrapper.text()).toContain('Electron 在线壳')
    expect(wrapper.text()).not.toContain('Electron 登录交接验证')
  })

  it('仅在桌面认证门禁启用时显示脱敏验证状态', async () => {
    let statusListener:
      | ((status: { phase: 'SUCCEEDED' }) => void)
      | undefined
    const start = vi.fn(async () => undefined)
    Object.defineProperty(window, 'yumpooDesktop', {
      configurable: true,
      value: Object.freeze({
        client: 'electron',
        auth: Object.freeze({
          isEnabled: vi.fn(async () => true),
          start,
          onStatus(listener: (status: { phase: 'SUCCEEDED' }) => void) {
            statusListener = listener
            return vi.fn()
          },
        }),
      }),
    })

    const wrapper = await mountApplication()
    await flushPromises()
    expect(wrapper.text()).toContain('Electron 登录交接验证')
    await wrapper.get('button').trigger('click')
    expect(start).toHaveBeenCalledOnce()

    statusListener?.({ phase: 'SUCCEEDED' })
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="desktop-auth-status"]').text()).toContain(
      '验证成功',
    )
    expect(wrapper.text()).not.toContain('codeVerifier')
  })
})
