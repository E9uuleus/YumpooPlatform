import { ProjectActorAccess, ProjectLifecycle } from '@yumpoo/api-client'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { afterEach, expect, it } from 'vitest'
import { useProjectRecents } from './useProjectRecents'

enableAutoUnmount(afterEach)
it('旧最近项目中的退役元数据被忽略，置顶和访问记录保持有效', () => {
  const entry = { id: 'p1', code: 'P001', name: '项目', lifecycle: ProjectLifecycle.Active, ownerUserId: 'u1', ownerDisplayName: '张三', actorAccess: ProjectActorAccess.Owner, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z', openedAt: 1, pinned: true, ['project' + 'Type']: 'LEGACY' }
  window.localStorage.setItem('yumpoo.projects.recents.v1.up2-recents', JSON.stringify([{ ...entry, lifecycle: 'DR' + 'AFT' }]))
  const wrapper = mount(defineComponent({ setup: () => useProjectRecents(() => 'up2-recents'), template: '<div />' }))
  expect(wrapper.vm.items).toHaveLength(1)
  expect(wrapper.vm.items[0]?.lifecycle).toBe(ProjectLifecycle.Active)
  wrapper.vm.record({ ...entry, name: '新名称' })
  expect(wrapper.vm.items[0]).toMatchObject({ name: '新名称', pinned: true })
  expect(wrapper.vm.items[0]).not.toHaveProperty('project' + 'Type')
  wrapper.vm.togglePinned('p1')
  expect(wrapper.vm.items[0]?.pinned).toBe(false)
})
