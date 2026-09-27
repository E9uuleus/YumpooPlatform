import { describe, expect, it } from 'vitest'
import { createRouter, createMemoryHistory } from 'vue-router'
import { AuthenticationRole, type CurrentAuthentication } from '@yumpoo/api-client'
import { routes, sessionDestination } from './index'
import { useSession } from '../composables/useSession'

describe('运维页面访问控制', () => {
  it('五个页面均继承平台管理员权限，公司管理员不能访问', () => {
    const router=createRouter({history:createMemoryHistory(),routes})
    const session=useSession();session.phase.value='authenticated'
    for(const role of [AuthenticationRole.CompanyAdmin,AuthenticationRole.AppManager]) {
      session.authentication.value={roles:new Set([role]),user:{workspaceSlug:'test'}} as CurrentAuthentication
      for(const page of ['overview','host','sessions','logs','alerts']) {
        const route=router.resolve('/admin/operations/'+page)
        expect(sessionDestination({ ...route, name: route.name ?? undefined })).toEqual(role===AuthenticationRole.AppManager?true:{name:'forbidden'})
      }
    }
    session.authentication.value=undefined;session.phase.value='checking'
  })
})
