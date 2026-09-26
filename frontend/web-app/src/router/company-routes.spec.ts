import { describe, expect, it } from 'vitest'
import { AuthenticationRole } from '@yumpoo/api-client'
import { routes } from './index'

describe('公司管理路由', () => {
  it('发布概览、同步运行和成员管理三个直达页面', () => {
    const shell = routes.find(route => route.path === '/')
    const identity = shell?.children?.find(route => route.path === 'admin/company')

    expect(identity?.redirect).toBe('/admin/company/overview')
    expect(identity?.meta?.requiredRoles).toEqual([
      AuthenticationRole.AppManager,
      AuthenticationRole.CompanyAdmin,
    ])
    expect(identity?.children?.map(child => child.path)).toEqual([
      'overview',
      'sync-runs',
      'members',
    ])
  })

  it('旧身份入口保留查询和 hash 重定向', () => {
    const shell = routes.find(route => route.path === '/')
    const legacy = shell?.children?.find(route => route.path.startsWith('admin/identity'))
    expect(typeof legacy?.redirect).toBe('function')
    if (typeof legacy?.redirect !== 'function') throw new Error('missing redirect')
    expect(legacy.redirect({ params: { section: 'members' }, query: { page: '2' }, hash: '#detail' } as never, {} as never))
      .toEqual({ path: '/admin/company/members', query: { page: '2' }, hash: '#detail' })
  })

  it('发布状态、拒绝和真实 404 页面', () => {
    expect(routes.map(route => route.path)).toEqual(expect.arrayContaining([
      '/status/account-disabled',
      '/status/upgrade-required',
      '/status/unavailable',
    ]))
    const shell = routes.find(route => route.path === '/')
    expect(shell?.children?.map(route => route.path)).toEqual(expect.arrayContaining([
      'forbidden',
      ':pathMatch(.*)*',
    ]))
  })
})
