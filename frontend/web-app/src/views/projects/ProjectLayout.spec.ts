import { ProjectLifecycle } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { notifyProjectLifecycleChanged } from '../../composables/projectLifecycleEvents'
import ProjectLayout from './ProjectLayout.vue'

const api = vi.hoisted(() => ({ getProject: vi.fn() }))
vi.mock('../../api/client', () => ({ projectsApi: api }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { projectId: 'archived-project' } }) }))
enableAutoUnmount(afterEach)
beforeEach(() => { api.getProject.mockReset() })

it('归档项目显示只读横幅，恢复事件更新项目状态', async () => {
  api.getProject.mockResolvedValue({ id: 'archived-project', lifecycle: ProjectLifecycle.Archived })
  const wrapper = mount(ProjectLayout, { global: { stubs: { RouterView: true } } })
  await flushPromises()
  expect(wrapper.text()).toContain('当前为只读浏览')
  api.getProject.mockResolvedValue({ id: 'archived-project', lifecycle: ProjectLifecycle.Active })
  notifyProjectLifecycleChanged('other-project')
  await flushPromises()
  expect(api.getProject).toHaveBeenCalledOnce()
  notifyProjectLifecycleChanged('archived-project')
  await flushPromises()
  expect(wrapper.find('.archived-project-banner').exists()).toBe(false)
})
