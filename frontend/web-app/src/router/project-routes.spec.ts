import { describe, expect, it } from 'vitest'
import { routes } from './index'

describe('M2-07 Project 工作台路由', () => {
  it('发布目录及概览、成员、关联产品、设置直达页面', () => {
    const shell = routes.find(route => route.path === '/')
    const detail = shell?.children?.find(route => route.path === 'projects/:projectId')

    expect(shell?.children?.some(route => route.path === 'projects')).toBe(true)
    expect(detail?.children?.map(route => route.path)).toEqual([
      'overview',
      'members',
      'products',
      'settings',
    ])
  })
})
