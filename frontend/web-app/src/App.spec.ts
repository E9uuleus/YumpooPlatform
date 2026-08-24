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
    user: { id: crypto.randomUUID(), displayName: '测试用户', workspaceSlug: 'member' },
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

  it('默认展示个人工作台项目目录，普通成员不显示身份管理入口', async () => {
    const wrapper = await mountApplication('/workspace/member')
    await flushPromises()
    expect(wrapper.text()).toContain('项目管理')
    expect(wrapper.text()).toContain('Yumpoo 测试公司')
    expect(wrapper.text()).toContain('Web 浏览器')
    expect(wrapper.findAll('.module-rail__item-label').map(item => item.text())).toEqual(['工作台'])
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

  it('项目层级导航默认展开，管理项目与详情入口正确高亮', async () => {
    const catalog = await mountApplication('/workspace/member')
    await flushPromises()
    const catalogItems = catalog.findAll('.context-navigation > .global-navigation button')
    expect(catalogItems.map(item => item.text())).toEqual(['项目', '管理项目'])
    expect(catalogItems[0]?.attributes('aria-expanded')).toBe('true')
    expect(catalogItems[1]?.attributes('aria-current')).toBe('page')
    expect(catalogItems[0]?.find('svg[width="12"]').exists()).toBe(true)
    expect(catalogItems[1]?.find('svg[width="16"]').exists()).toBe(true)
    await catalogItems[0]?.trigger('click')
    expect(catalogItems[0]?.attributes('aria-expanded')).toBe('false')
    expect(catalog.get('#desktop-project-navigation').attributes('style')).toContain('display: none')
    catalog.unmount()

    const details = await mountApplication('/projects/project-42/members')
    await flushPromises()
    const detailItems = details.findAll('.context-navigation > .global-navigation button')
    expect(detailItems.map(item => item.text())).toEqual(['项目', '管理项目', '概览', '成员', '设置'])
    expect(detailItems.find(item => item.text() === '成员')?.attributes('aria-current')).toBe('page')
    details.unmount()
  })

  it('旧项目目录地址进入 404，项目详情深链继续可用', async () => {
    const removedCatalog = await mountApplication('/projects')
    expect(removedCatalog.text()).toContain('页面不存在')
    removedCatalog.unmount()

    const details = await mountApplication('/projects/project-42/overview')
    await flushPromises()
    expect(details.find('.module-rail__item.active').text()).toContain('工作台')
    expect(details.text()).toContain('概览')
    details.unmount()
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
    await wrapper.get('.login-action').trigger('click')
    expect(start).toHaveBeenCalledOnce()
  })
})
