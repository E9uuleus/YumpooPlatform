import { flushPromises } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { useSession } from '../composables/useSession'
import router from '../router'

describe('文档中心路由', () => {
  it('匿名会话可以访问文档首页与文档页：守卫和会话监听都不跳转登录', async () => {
    expect(await router.push('/docs')).toBeUndefined()
    useSession().phase.value = 'anonymous'
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('docs-home')
    expect(await router.push('/docs/guide/faq')).toBeUndefined()
    await flushPromises()
    expect(router.currentRoute.value.name).toBe('docs-page')
    expect(router.currentRoute.value.params.slug).toBe('guide/faq')
  })
})
