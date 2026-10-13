import { ProjectLifecycle } from '@yumpoo/api-client'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter, useRoute } from 'vue-router'
import { notifyProjectLifecycleChanged } from '../../composables/projectLifecycleEvents'
import ProjectLayout from './ProjectLayout.vue'

const api = vi.hoisted(() => ({ getProject: vi.fn() }))
vi.mock('../../api/client', () => ({ projectsApi: api }))
enableAutoUnmount(afterEach)
beforeEach(() => { api.getProject.mockReset() })

async function mountLayout(projectId: string) {
  const StatisticsPage = defineComponent({
    setup() {
      const initialProjectId = String(useRoute().params.projectId)
      return () => h('div', { 'data-testid': 'statistics-project' }, initialProjectId)
    },
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/projects/:projectId/statistics', name: 'project-statistics', component: StatisticsPage },
  ] })
  await router.push(`/projects/${projectId}/statistics`)
  await router.isReady()
  return { router, wrapper: mount(ProjectLayout, { global: { plugins: [router] } }) }
}

it('归档项目显示只读横幅，恢复事件更新项目状态', async () => {
  api.getProject.mockResolvedValue({ id: 'archived-project', lifecycle: ProjectLifecycle.Archived })
  const { wrapper } = await mountLayout('archived-project')
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

it('切换项目及前进后退时，统计子页重新读取当前项目', async () => {
  api.getProject.mockImplementation(({ projectId }: { projectId: string }) => Promise.resolve({ id: projectId, lifecycle: ProjectLifecycle.Active }))
  const { router, wrapper } = await mountLayout('project-a')
  await flushPromises()
  expect(wrapper.get('[data-testid="statistics-project"]').text()).toBe('project-a')
  await router.push('/projects/project-b/statistics')
  await flushPromises()
  expect(wrapper.get('[data-testid="statistics-project"]').text()).toBe('project-b')
  router.back()
  await vi.waitFor(() => expect(wrapper.get('[data-testid="statistics-project"]').text()).toBe('project-a'))
  router.forward()
  await vi.waitFor(() => expect(wrapper.get('[data-testid="statistics-project"]').text()).toBe('project-b'))
  expect(api.getProject).toHaveBeenLastCalledWith({ projectId: 'project-b' })
})
