import { describe, expect, it } from 'vitest'
import { routes } from './index'

describe('Project 工作台路由', () => {
  it('发布个人工作台规范目录，并保留 Content 工作项及项目详情深链', () => {
    const shell = routes.find(route => route.path === '/')
    const detail = shell?.children?.find(route => route.path === 'projects/:projectId')

    expect(shell?.children?.find(route => route.path === '')?.name).toBe('workspace-root')
    expect(shell?.children?.find(route => route.path === 'workspace')?.name).toBe('workspace-entry')
    expect(shell?.children?.find(route => route.path === 'workspace/:workspaceSlug')?.name).toBe('workspace')
    expect(shell?.children?.some(route => route.path === 'projects')).toBe(false)
    expect(shell?.children?.some(route => route.name === 'home')).toBe(false)
    expect(detail?.meta?.shellSection).toBe('work')
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
