import {
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  type CurrentAuthentication,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { useSession } from './composables/useSession'
import { routes } from './router'

function authentication(
  type = AuthenticationClientType.Web,
  roles = new Set([AuthenticationRole.CompanyMember]),
): CurrentAuthentication {
  return {
    user: { id: crypto.randomUUID(), displayName: '测试用户' },
    company: {
      id: crypto.randomUUID(), displayName: 'Yumpoo 测试公司', timezone: 'Asia/Shanghai', weekStartDay: 'MONDAY',
    },
    roles,
    client: { type, compatibility: ClientCompatibility.Supported },
  } as CurrentAuthentication
}

async function mountApplication(path = '/') {
  const router = createRouter({ history: createMemoryHistory(), routes })
  await router.push(path)
  await router.isReady()
  return mount(App, { global: { plugins: [router] } })
}

describe('M1-12 Web 全局壳', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
    })))
    const session = useSession()
    session.phase.value = 'authenticated'
    session.authentication.value = authentication()
    session.actionProblem.value = undefined
  })

  afterEach(() => {
    Reflect.deleteProperty(window, 'yumpooDesktop')
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('显示正式首页、当前主体，普通成员不显示管理入口', async () => {
    const wrapper = await mountApplication()
    expect(wrapper.text()).toContain('欢迎回来，测试用户')
    expect(wrapper.text()).toContain('Yumpoo 测试公司')
    expect(wrapper.text()).toContain('Web 浏览器')
    expect(wrapper.findAll('.global-navigation button').map(item => item.text())).not.toContain('身份管理')
  })

  it('管理员显示全局身份入口且保留三个子入口', async () => {
    useSession().authentication.value = authentication(
      AuthenticationClientType.Web,
      new Set([AuthenticationRole.CompanyAdmin]),
    )
    const wrapper = await mountApplication('/admin/identity/overview')
    expect(wrapper.text()).toContain('身份管理')
    expect(wrapper.text()).toContain('概览')
    expect(wrapper.text()).toContain('同步运行')
    expect(wrapper.text()).toContain('成员管理')
  })

  it('Electron 匿名登录页仅通过受控桥打开系统浏览器', async () => {
    const start = vi.fn(async () => undefined)
    Object.defineProperty(window, 'yumpooDesktop', {
      configurable: true,
      value: Object.freeze({
        client: 'electron',
        auth: Object.freeze({
          isEnabled: vi.fn(async () => true),
          start,
          clear: vi.fn(async () => undefined),
          onStatus: vi.fn(() => vi.fn()),
        }),
      }),
    })
    const wrapper = await mountApplication('/login')
    await flushPromises()
    expect(wrapper.text()).toContain('在系统默认浏览器中完成')
    await wrapper.get('button').trigger('click')
    expect(start).toHaveBeenCalledOnce()
  })
})
