import { describe, expect, it } from 'vitest'
import { routes } from './index'

describe('M1-11 身份管理路由', () => {
  it('发布概览、同步运行和成员管理三个直达页面', () => {
    const identity = routes.find(route => route.path === '/admin/identity')

    expect(identity?.redirect).toBe('/admin/identity/overview')
    expect(identity?.children?.map(child => child.path)).toEqual([
      'overview',
      'sync-runs',
      'members',
    ])
  })
})
