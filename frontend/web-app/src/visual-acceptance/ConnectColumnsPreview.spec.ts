import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElRadioGroup } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, expect, it } from 'vitest'
import ConnectColumnsPreview from './ConnectColumnsPreview.vue'
import ConnectCell from '../components/projects/connect/ConnectCell.vue'
import IncomingConnectCell from '../components/projects/connect/IncomingConnectCell.vue'
import ProjectConnectionsOverview from '../components/projects/connect/ProjectConnectionsOverview.vue'
import ConnectionCardDialog from '../components/projects/connect/ConnectionCardDialog.vue'
import ConnectKanbanConnections from '../components/projects/connect/ConnectKanbanConnections.vue'

enableAutoUnmount(afterEach)
it('连接验收覆盖单/多目标、空单元格、只读、被连接、设置概览与缺省卡片字段', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }] })
  const wrapper = mount(ConnectColumnsPreview, { global: { plugins: [ElementPlus, router] } })
  await flushPromises()
  expect(wrapper.findAllComponents(ConnectCell)).toHaveLength(6)
  expect(wrapper.findAllComponents(ConnectCell).slice(0, 2).map(cell => cell.props('column')!.targets.length)).toEqual([1, 2])
  expect(wrapper.findAllComponents(ConnectCell).slice(2).every(cell => !cell.props('connections').length)).toBe(true)
  wrapper.findAllComponents(ElRadioGroup)[1]!.vm.$emit('update:modelValue', 'admin'); await flushPromises()
  expect(wrapper.findAllComponents(ConnectCell).every(cell => cell.props('readOnly'))).toBe(true)
  wrapper.findAllComponents(ElRadioGroup)[0]!.vm.$emit('update:modelValue', 'target'); await flushPromises()
  expect(wrapper.findAllComponents(IncomingConnectCell)).toHaveLength(2)
  wrapper.findAllComponents(ElRadioGroup)[0]!.vm.$emit('update:modelValue', 'settings'); await flushPromises()
  expect(wrapper.getComponent(ProjectConnectionsOverview).text()).toContain('移动端重构')
  await wrapper.findAll('.connect-preview__examples button')[1]!.trigger('click'); await flushPromises()
  expect(wrapper.getComponent(ConnectionCardDialog).props('connection').target).toMatchObject({ assignee: null, priority: null })
  wrapper.getComponent(ConnectionCardDialog).vm.$emit('update:open', false)
  wrapper.findAllComponents(ElRadioGroup)[0]!.vm.$emit('update:modelValue', 'filters'); await flushPromises()
  await wrapper.get('input[aria-label="产品缺陷 已连接"]').setValue(true)
  await wrapper.get('input[aria-label="产品缺陷 未连接"]').setValue(true)
  expect((wrapper.get('input[aria-label="产品缺陷 已连接"]').element as HTMLInputElement).checked).toBe(false)
  wrapper.findAllComponents(ElRadioGroup)[0]!.vm.$emit('update:modelValue', 'kanban'); await flushPromises()
  const chips = wrapper.findAllComponents(ConnectKanbanConnections)
  expect(chips.map(component => component.props('connections').length)).toEqual([5, 0])
  chips[0]!.vm.$emit('openCard', chips[0]!.props('connections')[0]); await flushPromises()
  expect(wrapper.getComponent(ConnectionCardDialog).props('readOnly')).toBe(true)
})
