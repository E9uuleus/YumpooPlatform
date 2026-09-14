import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ProjectWorkItemListItem } from '@yumpoo/api-client'
import WorkItemRowActions from './WorkItemRowActions.vue'

const api = vi.hoisted(() => ({
  listProjectWorkItems: vi.fn(), listWorkItemSubitems: vi.fn(), moveProjectWorkItemOrder: vi.fn(),
  moveWorkItemSubitemOrder: vi.fn(), createWorkItem: vi.fn(), createWorkItemSubitem: vi.fn(),
  deleteWorkItem: vi.fn(), archiveWorkItem: vi.fn(),
}))
vi.mock('../../api/client', () => ({ workItemsApi: api }))
vi.mock('@yumpoo/api-client', async original => ({ ...await original<typeof import('@yumpoo/api-client')>(), readCsrfToken: () => 'csrf' }))

function item(id = 'work-1'): ProjectWorkItemListItem {
  return { id, projectId: 'project-1', itemNo: 'PROJECT-1', title: '实现行菜单', contentId: 'content-1', subitemCount: 0,
    etag: '"3"', capabilities: { canEditFields: true, canMoveInProjectOrder: true, canDelete: true } } as ProjectWorkItemListItem
}
function render(props = {}) {
  return mount(WorkItemRowActions, { props: { item: item(), canCreate: true, sorted: false, ...props },
    global: { stubs: {
      ElDropdown: { emits: ['visible-change'], methods: { handleClose() { this.$emit('visible-change', false) } }, template: '<div><slot /><slot name="dropdown" /></div>' },
      ElDropdownMenu: { template: '<div><slot /></div>' },
      ElDropdownItem: { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\', $event)"><slot /></button>' },
      ElPopover: { template: '<div><slot name="reference" /><slot /></div>' },
      WorkItemParentPicker: { emits: ['close'], methods: { focusSearch() {} }, template: '<section data-test="parent-picker"><button @click="$emit(\'close\')">关闭父项选择</button></section>' },
    } } })
}
async function click(wrapper: ReturnType<typeof render>, text: string) {
  await wrapper.findAll('button').find(button => button.text().endsWith(text))!.trigger('click')
  await flushPromises()
}

describe('工作项行菜单', () => {
  beforeEach(() => {
    Object.values(api).forEach(mock => mock.mockReset())
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '下方新工作项', action: 'confirm' } as never)
    vi.spyOn(ElMessage, 'success').mockImplementation(() => ({ close() {} }))
    vi.spyOn(ElMessage, 'error').mockImplementation(() => ({ close() {} }))
    vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({ close() {} }))
  })
  afterEach(() => vi.restoreAllMocks())

  it('打开详情与添加子项复用父页面入口，键盘聚焦按钮可识别', async () => {
    const wrapper = render()
    expect(wrapper.get('.work-item-row-menu-trigger').attributes('aria-haspopup')).toBe('menu')
    await click(wrapper, '打开工作项')
    await click(wrapper, '添加子工作项')
    expect(wrapper.emitted('open')?.[0]).toEqual([item()])
    expect(wrapper.emitted('addSubitem')?.[0]).toEqual([item()])
    wrapper.unmount()
  })

  it('移动到底部读取所有分页，不携带当前筛选；请求保留版本和 CSRF', async () => {
    api.listProjectWorkItems.mockResolvedValueOnce({ items: [item(), item('middle')], nextCursor: 'page-2' })
      .mockResolvedValueOnce({ items: [item('last')], nextCursor: null })
    const wrapper = render()
    await click(wrapper, '移动至底部')
    expect(api.listProjectWorkItems).toHaveBeenLastCalledWith({ projectId: 'project-1', view: 'TABLE', limit: 100, cursor: 'page-2' })
    expect(api.moveProjectWorkItemOrder).toHaveBeenCalledWith(expect.objectContaining({
      workItemId: 'work-1', ifMatch: '"3"', xXSRFTOKEN: 'csrf', idempotencyKey: expect.any(String),
      projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: 'last', nextVisibleWorkItemId: null },
    }))
    expect(wrapper.emitted('moved')).toEqual([[item()]])
    expect(ElMessage.success).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('子项只在同一父项内排序，并隐藏两级层次不适用的菜单', async () => {
    api.listWorkItemSubitems.mockResolvedValue({ items: [item('first'), item()] })
    const wrapper = render({ parentId: 'parent-1' })
    expect(wrapper.text()).not.toContain('添加子工作项')
    expect(wrapper.text()).not.toContain('转为子工作项')
    await click(wrapper, '移动至顶部')
    expect(api.moveWorkItemSubitemOrder).toHaveBeenCalledWith(expect.objectContaining({ parentWorkItemId: 'parent-1', subitemId: 'work-1',
      projectWorkItemOrderMoveRequest: { previousVisibleWorkItemId: null, nextVisibleWorkItemId: 'first' } }))
    expect(api.moveProjectWorkItemOrder).not.toHaveBeenCalled()
    expect(wrapper.emitted('moved')).toEqual([[item()]])
    expect(ElMessage.success).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('排序开启时禁止调整顺序，已有子项时说明不能转换', () => {
    const wrapper = render({ sorted: true, item: { ...item(), subitemCount: 2 } })
    for (const text of ['移动至顶部', '移动至底部', '在下方创建新工作项', '转为子工作项']) {
      expect(wrapper.findAll('button').find(button => button.text().endsWith(text))!.attributes('disabled')).toBeDefined()
    }
    expect(wrapper.html()).toContain('已有子工作项，不能转为子工作项')
    wrapper.unmount()
  })

  it('复制稳定详情链接，不包含视图和筛选参数', async () => {
    const write = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    const wrapper = render()
    await click(wrapper, '复制工作项链接')
    const url = new URL(write.mock.calls[0]![0])
    expect(url.pathname).toBe('/projects/project-1/overview')
    expect(url.search).toBe('?workItemId=work-1')
    wrapper.unmount()
  })

  it('转换在菜单旁展开选择器，关闭后保留菜单，其他操作会收起选择器', async () => {
    const wrapper = render()
    await click(wrapper, '转为子工作项')
    expect(wrapper.find('[data-test="parent-picker"]').exists()).toBe(true)
    expect(wrapper.get('.row-action-convert').attributes('aria-expanded')).toBe('true')
    await wrapper.get('[popper-class="work-item-parent-popover"]').trigger('pointermove')
    expect(wrapper.find('[data-test="parent-picker"]').exists()).toBe(true)
    await click(wrapper, '关闭父项选择')
    expect(wrapper.find('[data-test="parent-picker"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('打开工作项')
    await click(wrapper, '转为子工作项')
    await click(wrapper, '打开工作项')
    expect(wrapper.find('[data-test="parent-picker"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('下方创建仅通知表格插入草稿，不弹窗或提前创建', async () => {
    const wrapper = render()
    await click(wrapper, '在下方创建新工作项')
    expect(wrapper.emitted('createBelow')).toEqual([[item()]])
    expect(ElMessageBox.prompt).not.toHaveBeenCalled()
    expect(api.createWorkItem).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('子项的下方创建同样交由所在表格处理', async () => {
    const wrapper = render({ parentId: 'parent-1' })
    await click(wrapper, '在下方创建新工作项')
    expect(wrapper.emitted('createBelow')).toEqual([[item()]])
    expect(api.createWorkItemSubitem).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('归档与软删除调用不同接口，取消确认不会写入', async () => {
    const wrapper = render()
    await click(wrapper, '归档')
    expect(api.archiveWorkItem).toHaveBeenCalledWith(expect.objectContaining({ workItemId: 'work-1', ifMatch: '"3"' }))
    expect(api.deleteWorkItem).not.toHaveBeenCalled()
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    await click(wrapper, '删除')
    expect(api.deleteWorkItem).not.toHaveBeenCalled()
    await click(wrapper, '删除')
    expect(api.deleteWorkItem).toHaveBeenCalledWith(expect.objectContaining({ workItemDeleteRequest: { reason: '通过工作项行菜单删除' } }))
    wrapper.unmount()
  })

  it('草稿离开保护拒绝时不执行归档，权限不足时禁用写操作', async () => {
    const wrapper = render({ beforeRemove: async () => false })
    await click(wrapper, '归档')
    expect(api.archiveWorkItem).not.toHaveBeenCalled()
    expect(ElMessageBox.confirm).not.toHaveBeenCalled()
    await wrapper.setProps({ canCreate: false, item: { ...item(), capabilities: { canEditFields: false, canMoveInProjectOrder: false, canDelete: false } } as ProjectWorkItemListItem })
    const enabled = wrapper.findAll('button').filter(button => button.text() && button.attributes('disabled') === undefined).map(button => button.text())
    expect(enabled).toEqual(['↗打开工作项', '复制工作项链接'])
    wrapper.unmount()
  })
})
