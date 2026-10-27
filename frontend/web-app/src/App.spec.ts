import {
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  type CurrentAuthentication,
} from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'
import { operationsApi } from './api/client'
import { useSession } from './composables/useSession'
import { routes } from './router'

enableAutoUnmount(afterEach)

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
    vi.useRealTimers()
    Reflect.deleteProperty(window, 'yumpooDesktop')
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('默认展示个人工作台项目目录，普通成员不显示公司管理入口', async () => {
    const wrapper = await mountApplication('/workspace/member')
    await flushPromises()
    expect(wrapper.text()).toContain('项目管理')
    expect(wrapper.text()).toContain('Yumpoo 测试公司')
    expect(wrapper.text()).not.toContain('在线壳')
    expect(wrapper.text()).not.toContain('Web 浏览器')
    expect(wrapper.findAll('.module-rail__item-label').map(item => item.text())).toEqual(['首页', '工作台'])
  })

  it('顶栏图标按钮：本页引导随页面启用，偏好设置进入设置页', async () => {
    const wrapper = await mountApplication('/settings')
    await flushPromises()
    const tour = wrapper.get('[data-tour="topbar-tour"]')
    expect(tour.attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-tour="topbar-appearance"]').exists()).toBe(false)
    const router = wrapper.vm.$router
    await router.push('/')
    await flushPromises()
    expect(wrapper.get('[data-tour="topbar-tour"]').attributes('disabled')).toBeUndefined()
    await router.push('/inbox')
    await flushPromises()
    await wrapper.get('[data-tour="topbar-preferences"]').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('settings')
    expect(wrapper.get('[data-tour="topbar-preferences"]').classes()).toContain('is-active')
  })

  it('管理员显示全局身份入口且保留三个子入口', async () => {
    useSession().authentication.value = authentication(
      AuthenticationClientType.Web,
      new Set([AuthenticationRole.CompanyAdmin]),
    )
    const wrapper = await mountApplication('/admin/identity/overview')
    expect(wrapper.text()).toContain('公司管理')
    expect(wrapper.text()).toContain('概览')
    expect(wrapper.text()).toContain('同步运行')
    expect(wrapper.text()).toContain('成员与角色')
  })

  it('首页和桌面、移动导航共用告警汇总及轮询', async () => {
    vi.useFakeTimers()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    useSession().authentication.value = authentication(
      AuthenticationClientType.Web,
      new Set([AuthenticationRole.AppManager]),
    )
    const summary = vi.spyOn(operationsApi, 'getOperationsAlertSummary').mockResolvedValue({ critical: 1, warning: 2 })
    const wrapper = await mountApplication('/')
    await flushPromises()
    expect(wrapper.text()).toContain('运维告警 3')
    expect(wrapper.get('.module-rail .operations-badge').text()).toBe('3')
    expect(summary).toHaveBeenCalledOnce()

    await wrapper.get('[aria-label="打开导航"]').trigger('click')
    await flushPromises()
    expect(summary).toHaveBeenCalledOnce()
    summary.mockResolvedValue({ critical: 2, warning: 3 })
    await vi.advanceTimersByTimeAsync(60000)
    await flushPromises()
    expect(summary).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('运维告警 5')
    expect(wrapper.get('.module-rail .operations-badge').text()).toBe('5')

    window.dispatchEvent(new Event('yumpoo-operations-alerts-changed'))
    await flushPromises()
    expect(summary).toHaveBeenCalledTimes(3)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(60000)
    expect(summary).toHaveBeenCalledTimes(3)
  })

  it('项目层级导航默认展开，管理项目与详情入口正确高亮', async () => {
    const catalog = await mountApplication('/workspace/member')
    await flushPromises()
    const catalogItems = catalog.findAll('.context-navigation > .global-navigation button')
    expect(catalogItems.map(item => item.text())).toEqual(['仪表板', '我的工时', '项目', '管理项目', '归档项目'])
    expect(catalogItems[2]?.attributes('aria-expanded')).toBe('true')
    expect(catalogItems[3]?.attributes('aria-current')).toBe('page')
    expect(catalogItems[2]?.find('svg[width="12"]').exists()).toBe(true)
    expect(catalogItems[3]?.find('svg[width="16"]').exists()).toBe(true)
    await catalogItems[2]?.trigger('click')
    expect(catalogItems[2]?.attributes('aria-expanded')).toBe('false')
    expect(catalog.get('#desktop-project-navigation').attributes('style')).toContain('display: none')
    catalog.unmount()

    const details = await mountApplication('/projects/project-42/members')
    await flushPromises()
    const detailItems = details.findAll('.context-navigation > .global-navigation button')
    expect(detailItems.map(item => item.text())).toEqual(['仪表板', '我的工时', '项目', '管理项目', '归档项目', '工作项', '统计', '成员', '动态', '设置'])
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
    expect(details.text()).toContain('工作项')
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

  it('Electron 等待浏览器回调时可重新打开或取消，并按勾选传递 7 天免登录', async () => {
    let publish: ((status: unknown) => void) | undefined
    const start = vi.fn(async () => undefined)
    const cancel = vi.fn(async () => undefined)
    Object.defineProperty(window, 'yumpooDesktop', {
      configurable: true,
      value: Object.freeze({
        client: 'electron',
        auth: Object.freeze({
          isEnabled: vi.fn(async () => true),
          start,
          cancel,
          clear: vi.fn(async () => undefined),
          onStatus: vi.fn((listener: (status: unknown) => void) => {
            publish = listener
            return vi.fn()
          }),
        }),
      }),
    })
    const wrapper = await mountApplication('/login')
    await flushPromises()
    await wrapper.get('.login-remember input').setValue(false)
    publish?.({ phase: 'WAITING_FOR_CALLBACK' })
    await flushPromises()

    expect(wrapper.text()).toContain('已在系统浏览器中打开登录页')
    expect(wrapper.get('.login-action').attributes('disabled')).toBeUndefined()
    await wrapper.get('.login-action').trigger('click')
    expect(start).toHaveBeenCalledWith({ remember: false })
    await wrapper.get('.login-action--secondary').trigger('click')
    expect(cancel).toHaveBeenCalledOnce()
    localStorage.removeItem('yumpoo.auth.remember.v1')
  })

  it('Electron 登录失败时区分登录服务与浏览器，并为未知错误码兜底', async () => {
    let publish: ((status: unknown) => void) | undefined
    Object.defineProperty(window, 'yumpooDesktop', {
      configurable: true,
      value: Object.freeze({
        client: 'electron',
        auth: Object.freeze({
          isEnabled: vi.fn(async () => true),
          start: vi.fn(async () => undefined),
          clear: vi.fn(async () => undefined),
          onStatus: vi.fn((listener: (status: unknown) => void) => {
            publish = listener
            return vi.fn()
          }),
        }),
      }),
    })
    const wrapper = await mountApplication('/login')
    await flushPromises()

    publish?.({ phase: 'FAILED', errorCode: 'AUTH_SERVICE_FAILED' })
    await flushPromises()
    expect(wrapper.text()).toContain('无法连接登录服务')
    expect(wrapper.text()).not.toContain('无法打开系统浏览器')

    publish?.({ phase: 'FAILED', errorCode: 'SOMETHING_NEW' })
    await flushPromises()
    expect(wrapper.text()).toContain('桌面登录未完成，请重试')
  })
})
