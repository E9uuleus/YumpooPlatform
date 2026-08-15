import { describe, expect, it } from 'vitest'
import { AuthenticationRole } from '@yumpoo/api-client'
import { routes } from './index'

describe('M1-12 身份管理路由', () => {
  it('发布概览、同步运行和成员管理三个直达页面', () => {
    const shell = routes.find(route => route.path === '/')
    const identity = shell?.children?.find(route => route.path === 'admin/identity')

    expect(identity?.redirect).toBe('/admin/identity/overview')
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
