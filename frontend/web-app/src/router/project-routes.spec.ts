import { describe, expect, it } from 'vitest'
import { routes } from './index'

describe('Project 工作台路由', () => {
  it('发布目录、Content 工作项深链及其余直达页面', () => {
    const shell = routes.find(route => route.path === '/')
    const detail = shell?.children?.find(route => route.path === 'projects/:projectId')

    expect(shell?.children?.some(route => route.path === 'projects')).toBe(true)
    expect(detail?.children?.map(route => route.path)).toEqual([
      'overview',
      'contents',
      'contents/:contentId',
      'members',
      'products',
      'settings',
    ])
  })
})
