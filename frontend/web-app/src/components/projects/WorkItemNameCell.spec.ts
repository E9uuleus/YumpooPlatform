import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import type { ProjectWorkItemListItem, WorkItemDetail } from '@yumpoo/api-client'
import WorkItemNameCell from './WorkItemNameCell.vue'

enableAutoUnmount(afterEach)
const api = vi.hoisted(() => ({ getWorkItem: vi.fn(), updateWorkItem: vi.fn() }))
vi.mock('../../api/client', () => ({ workItemsApi: api }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))

const item = { id: 'item-1', title: '原名称', etag: '"1"', capabilities: { canEditFields: true } } as ProjectWorkItemListItem
const detail = {
  ...item, etag: '"5"', priority: 'HIGH', assigneeUserId: 'member-2', description: '保留描述', notes: '保留备注',
  timelineStartDate: new Date('2026-09-01'), timelineEndDate: new Date('2026-09-18'),
  dueDate: new Date('2026-09-19'), dueTime: '18:30',
} as unknown as WorkItemDetail

describe('工作项名称单元格', () => {
  beforeEach(() => {
    api.getWorkItem.mockReset().mockResolvedValue(detail)
    api.updateWorkItem.mockReset().mockImplementation(({ workItemUpdateRequest }) => Promise.resolve({ ...detail, ...workItemUpdateRequest }))
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close() {} }))
  })
  afterEach(() => vi.restoreAllMocks())

  it('只有文字启动编辑，空白和展开子项不启动；详情按钮独立打开详情', async () => {
    const wrapper = mount(WorkItemNameCell, { props: { item }, slots: { prefix: '<button class="expand">展开</button>' } })
    await wrapper.trigger('click')
    await wrapper.get('.expand').trigger('click')
    expect(wrapper.find('input').exists()).toBe(false)
    await wrapper.get('.work-item-detail-button').trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
    expect(wrapper.find('input').exists()).toBe(false)
    await wrapper.get('.work-item-title-text').trigger('click')
    expect(wrapper.classes()).toContain('work-item-name-cell--editing')
    expect(wrapper.classes()).toContain('monday-cell--selected')
    expect(wrapper.get('input').element.value).toBe(item.title)
  })

  it('点击外部自动保存并保留最新详情中的其他字段和版本', async () => {
    const wrapper = mount(WorkItemNameCell, { props: { item }, attachTo: document.body })
    await wrapper.get('.work-item-title-text').trigger('click')
    await wrapper.get('input').setValue('  新名称  ')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await wrapper.get('input').trigger('blur')
    await flushPromises()
    expect(api.updateWorkItem).toHaveBeenCalledTimes(1)
    expect(api.updateWorkItem).toHaveBeenCalledWith({ workItemId: item.id, xXSRFTOKEN: 'csrf', ifMatch: '"5"', workItemUpdateRequest: {
      title: '新名称', priority: 'HIGH', assigneeUserId: 'member-2', description: '保留描述', notes: '保留备注',
      timelineStartDate: detail.timelineStartDate, timelineEndDate: detail.timelineEndDate, dueDate: detail.dueDate, dueTime: '18:30',
    } })
    expect(wrapper.emitted('updated')?.[0]?.[1]).toMatchObject({ title: '新名称' })
    expect(wrapper.find('input').exists()).toBe(false)
  })

  it('中文输入中的 Enter 不提交，Escape、空名称和未修改名称不写入', async () => {
    const wrapper = mount(WorkItemNameCell, { props: { item } })
    await wrapper.get('.work-item-title-text').trigger('click')
    await wrapper.get('input').setValue('输入中')
    await wrapper.get('input').trigger('keydown', { key: 'Enter', isComposing: true })
    expect(api.getWorkItem).not.toHaveBeenCalled()
    await wrapper.get('input').trigger('keydown', { key: 'Escape' })
    await wrapper.get('.work-item-title-text').trigger('click')
    await wrapper.get('input').trigger('blur')
    await wrapper.get('.work-item-title-text').trigger('click')
    await wrapper.get('input').setValue('  ')
    await wrapper.get('input').trigger('blur')
    expect(wrapper.find('input').exists()).toBe(false)
    expect(api.updateWorkItem).not.toHaveBeenCalled()
  })

  it('保存失败保留输入并恢复焦点，重试使用重新读取的版本', async () => {
    api.updateWorkItem.mockRejectedValueOnce(new Error('conflict'))
    const wrapper = mount(WorkItemNameCell, { props: { item }, attachTo: document.body })
    await wrapper.get('.work-item-title-text').trigger('click')
    await wrapper.get('input').setValue('重试名称')
    await wrapper.get('input').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(wrapper.get('input').element.value).toBe('重试名称')
    expect(document.activeElement).toBe(wrapper.get('input').element)
    api.getWorkItem.mockResolvedValue({ ...detail, etag: '"6"' })
    await wrapper.get('input').trigger('keydown', { key: 'Enter' })
    await flushPromises()
    expect(api.updateWorkItem).toHaveBeenLastCalledWith(expect.objectContaining({ ifMatch: '"6"' }))
    expect(wrapper.find('input').exists()).toBe(false)
  })

  it('点击外部时等待输入法组合结束，避免保存尚未确认的旧文本', async () => {
    const wrapper = mount(WorkItemNameCell, { props: { item } })
    await wrapper.get('.work-item-title-text').trigger('click')
    const input = wrapper.get('input')
    await input.trigger('compositionstart')
    input.element.value = '中文完整名称'
    await input.trigger('input')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await input.trigger('blur')
    expect(api.updateWorkItem).not.toHaveBeenCalled()
    await input.trigger('compositionend')
    await flushPromises()
    expect(api.updateWorkItem).toHaveBeenCalledWith(expect.objectContaining({
      workItemUpdateRequest: expect.objectContaining({ title: '中文完整名称' }),
    }))
  })

  it('只读工作项仍可打开详情，但不能进入名称编辑', async () => {
    const wrapper = mount(WorkItemNameCell, { props: { item: { ...item, capabilities: { ...item.capabilities, canEditFields: false } } } })
    await wrapper.get('.work-item-title-text').trigger('click')
    expect(wrapper.find('input').exists()).toBe(false)
    await wrapper.get('.work-item-detail-button').trigger('click')
    expect(wrapper.emitted('open')).toHaveLength(1)
  })

  it.each(['', '   '])('草稿名称只作占位，空白值 %j 失焦取消且不请求创建', async value => {
    const create = vi.fn().mockResolvedValue(true)
    const wrapper = mount(WorkItemNameCell, { props: { item: { ...item, title: '' }, create }, attachTo: document.body })
    await flushPromises()
    const input = wrapper.get('input')
    expect(document.activeElement).toBe(input.element)
    expect(input.element.value).toBe('')
    expect(input.attributes('placeholder')).toBe('*新工作项')
    expect(create).not.toHaveBeenCalled()
    await input.setValue(value)
    await input.trigger('blur')
    await flushPromises()
    expect(create).not.toHaveBeenCalled()
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(api.updateWorkItem).not.toHaveBeenCalled()
  })
})
