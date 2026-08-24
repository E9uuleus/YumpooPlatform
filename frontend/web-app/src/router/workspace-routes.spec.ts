import {
  AuthenticationClientType,
  AuthenticationRole,
  ClientCompatibility,
  CurrentAuthenticationCompanyWeekStartDayEnum,
  type CurrentAuthentication,
} from '@yumpoo/api-client'
import { beforeEach, describe, expect, it } from 'vitest'
import type { RouteLocationNormalized } from 'vue-router'
import { useSession } from '../composables/useSession'
import router, { sessionDestination } from './index'

const authentication: CurrentAuthentication = {
  user: {
    id: '00000000-0000-4000-8000-000000000101',
    displayName: '杭州江上雨',
    workspaceSlug: 'hanzhoujiangshangyu',
  },
  company: {
    id: '00000000-0000-4000-8000-000000000001',
    displayName: 'Yumpoo',
    timezone: 'Asia/Shanghai',
    weekStartDay: CurrentAuthenticationCompanyWeekStartDayEnum.Monday,
  },
  roles: new Set([AuthenticationRole.CompanyMember]),
  client: {
    type: AuthenticationClientType.Web,
    compatibility: ClientCompatibility.Supported,
  },
}

function destination(path: string) {
  return sessionDestination(router.resolve(path) as RouteLocationNormalized)
}

describe('个人工作台规范路由守卫', () => {
  beforeEach(() => {
    const session = useSession()
    session.phase.value = 'authenticated'
    session.authentication.value = authentication
  })

  it.each(['/', '/workspace'])(
    '%s 替换为当前用户规范地址',
    path => {
      expect(destination(path)).toEqual({
        name: 'workspace',
        params: { workspaceSlug: 'hanzhoujiangshangyu' },
        replace: true,
      })
    },
  )

  it('错误或其他用户别名不查询身份，直接替换为当前用户地址', () => {
    expect(destination('/workspace/someone-else')).toEqual({
      name: 'workspace',
      params: { workspaceSlug: 'hanzhoujiangshangyu' },
      replace: true,
    })
    expect(destination('/workspace/hanzhoujiangshangyu')).toBe(true)
  })

  it('旧项目目录进入 404，项目详情深链保持工作台区域', () => {
    const removedCatalog = router.resolve('/projects')
    const projectDetail = router.resolve('/projects/project-42/overview')

    expect(removedCatalog.name).toBe('not-found')
    expect(destination('/projects')).toBe(true)
    expect(projectDetail.name).toBe('project-overview')
    expect(projectDetail.meta.shellSection).toBe('work')
    expect(destination('/projects/project-42/overview')).toBe(true)
  })
})
