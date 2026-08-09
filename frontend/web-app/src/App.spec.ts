import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, describe, expect, it } from 'vitest'
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
  })
})
