import {
  WorkItemLabelColorToken,
  type Content,
  type ProjectContentCatalog,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { contentsApi } from '../../api/client'
import WorkItemContentPopoverContent from './WorkItemContentPopoverContent.vue'

vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

vi.mock('../../api/client', () => ({
  contentsApi: {
    listProjectContents: vi.fn(),
    createContent: vi.fn(),
    updateContent: vi.fn(),
    deleteContent: vi.fn(),
  },
}))

const floatingStubs = {
  ElPopover: { template: '<div><slot name="reference" /><slot /></div>' },
  ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: {
    props: ['disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
  },
}

const now = new Date('2026-09-02T00:00:00Z')

function content(overrides: Partial<Content>): Content {
  return {
    id: 'content-default', projectId: 'project-1', code: 'REQUIREMENTS', name: '需求',
    colorToken: WorkItemLabelColorToken.BrightBlue, sortOrder: 10, active: true,
    protectedContent: true, inUse: true, rowVersion: 0, createdAt: now,
    createdByUserId: 'owner-1', updatedAt: now, updatedByUserId: 'owner-1',
    ...overrides,
  }
}

function catalog(): ProjectContentCatalog {
  return {
    rowVersion: 3, etag: '"3"', canManage: true,
    items: [
      content({}),
      content({ id: 'content-task', code: 'TASKS', name: '任务',
        colorToken: WorkItemLabelColorToken.BrightGreen, sortOrder: 20,
        protectedContent: false, inUse: true }),
      content({ id: 'content-defect', code: 'DEFECTS', name: '缺陷',
        colorToken: WorkItemLabelColorToken.DarkRed, sortOrder: 30, active: false,
        protectedContent: false, inUse: false }),
    ],
  }
}

describe('WorkItemContentPopoverContent', () => {
  beforeEach(() => vi.clearAllMocks())

  it('选择态只显示启用类别和当前停用类别，并使用优先级弹窗的布局结构', async () => {
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: catalog(), currentValue: 'content-defect', canManage: true },
    })

    expect(wrapper.classes()).toContain('yp-label-popover-root--select')
    expect(wrapper.findAll('.content-pill')).toHaveLength(3)
    expect(wrapper.find('.content-option--inactive').text()).toContain('缺陷')
    expect(wrapper.find('.content-pill').attributes('style')).toContain('--yp-label-bright-blue')
    expect(wrapper.find('.content-manage').text()).toBe('编辑')
    expect(wrapper.find('.content-manage .edit-icon').exists()).toBe(true)

    await wrapper.findAll('.content-option')[1]?.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['content-task'])
  })

  it('非当前停用类别不可供选择，且无管理权限时隐藏编辑按钮', () => {
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: catalog(), currentValue: 'content-default', canManage: false },
    })

    expect(wrapper.findAll('.content-pill').map(node => node.text())).toEqual(['需求', '任务'])
    expect(wrapper.find('.content-manage').exists()).toBe(false)
  })

  it('编辑态提供弹跳、多列、油漆桶色板、拖拽手柄和更多菜单', async () => {
    const items = Array.from({ length: 7 }, (_, index) => content({
      id: `content-${index}`,
      code: `CATEGORY_${index}`,
      name: `类别 ${index + 1}`,
      sortOrder: (index + 1) * 10,
      protectedContent: false,
      inUse: false,
    }))
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: { ...catalog(), items }, canManage: true },
      global: { stubs: floatingStubs },
    })

    await wrapper.find('.content-manage').trigger('click')

    expect(wrapper.classes()).toContain('yp-label-popover-root--edit')
    expect(wrapper.find('.content-edit-view').classes()).toContain('jelly-wobble')
    expect(wrapper.findAll('.content-column')).toHaveLength(2)
    expect(wrapper.findAll('.content-editor-row')).toHaveLength(7)
    expect(wrapper.findAll('.drag-handle-btn')).toHaveLength(7)
    expect(wrapper.findAll('.color-square-btn')).toHaveLength(7)
    expect(wrapper.findAll('.color-swatch-item')).toHaveLength(7 * 33)
    expect(wrapper.findAll('.more-options-btn')).toHaveLength(7)
    expect(wrapper.find('.apply-action-btn').text()).toBe('应用')
  })

  it('更多菜单遵守停用和删除约束，并在应用前只修改草稿', async () => {
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: catalog(), canManage: true },
      global: { stubs: floatingStubs },
    })

    await wrapper.find('.content-manage').trigger('click')
    const deleteButtons = wrapper.findAll('.content-delete')
    expect(deleteButtons[0]?.attributes('disabled')).toBeDefined()
    expect(deleteButtons[1]?.attributes('disabled')).toBeDefined()
    expect(deleteButtons[2]?.attributes('disabled')).toBeUndefined()

    await wrapper.findAll('.content-toggle')[0]?.trigger('click')
    expect(wrapper.findAll('.content-toggle')[0]?.text()).toContain('启用类别')
    expect(contentsApi.updateContent).not.toHaveBeenCalled()

    await deleteButtons[2]?.trigger('click')
    expect(wrapper.findAll('.content-editor-row')).toHaveLength(2)
    expect(contentsApi.deleteContent).not.toHaveBeenCalled()
  })

  it('最后一个启用类别不可停用或删除', async () => {
    const onlyActive = catalog()
    onlyActive.items = onlyActive.items.map((item, index) => ({ ...item, active: index === 0 }))
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: onlyActive, canManage: true },
      global: { stubs: floatingStubs },
    })

    await wrapper.find('.content-manage').trigger('click')
    expect(wrapper.findAll('.content-toggle')[0]?.attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.content-delete')[0]?.attributes('disabled')).toBeDefined()
  })

  it('支持拖拽重排并新增类别，不在应用前调用 API', async () => {
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: catalog(), canManage: true },
      global: { stubs: floatingStubs },
    })

    await wrapper.find('.content-manage').trigger('click')
    const rows = wrapper.findAll('.content-editor-row')
    const dataTransfer = { effectAllowed: '', dropEffect: '', setData: vi.fn() }
    await rows[0]?.trigger('dragstart', { dataTransfer })
    await rows[2]?.trigger('drop')
    expect(wrapper.findAll('.content-name-input input').map(input => (input.element as HTMLInputElement).value))
      .toEqual(['任务', '缺陷', '需求'])

    await wrapper.find('.new-content-action-btn').trigger('click')
    expect(wrapper.findAll('.content-editor-row')).toHaveLength(4)
    expect((wrapper.findAll('.content-name-input input').at(-1)?.element as HTMLInputElement).value).toBe('新类别')
    expect(contentsApi.createContent).not.toHaveBeenCalled()
  })

  it('颜色更改仅在点击应用后提交，并返回最新目录', async () => {
    const initial = catalog()
    const updated = {
      ...initial,
      rowVersion: 4,
      etag: '"4"',
      items: initial.items.map(item => item.id === 'content-default'
        ? { ...item, colorToken: WorkItemLabelColorToken.BrightGreen }
        : item),
    }
    vi.mocked(contentsApi.listProjectContents)
      .mockResolvedValueOnce(initial)
      .mockResolvedValueOnce(updated)
    vi.mocked(contentsApi.updateContent).mockResolvedValue(updated.items[0]!)
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: initial, canManage: true },
      global: { stubs: floatingStubs },
    })

    await wrapper.find('.content-manage').trigger('click')
    await wrapper.find('.color-swatch-item').trigger('click')
    expect(contentsApi.updateContent).not.toHaveBeenCalled()

    await wrapper.find('.apply-action-btn').trigger('click')
    await flushPromises()

    expect(contentsApi.updateContent).toHaveBeenCalledWith(expect.objectContaining({
      projectId: 'project-1',
      contentId: 'content-default',
      ifMatch: '"3"',
      contentUpdateRequest: expect.objectContaining({ colorToken: WorkItemLabelColorToken.BrightGreen }),
    }))
    expect(wrapper.emitted('updated')?.[0]).toEqual([updated])
    expect(wrapper.classes()).toContain('yp-label-popover-root--select')
  })

  it('外部关闭时重置选择态并丢弃未应用的新类别', async () => {
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: catalog(), canManage: true },
      global: { stubs: floatingStubs },
    })

    await wrapper.find('.content-manage').trigger('click')
    await wrapper.find('.new-content-action-btn').trigger('click')
    wrapper.vm.resetEditor()
    await wrapper.vm.$nextTick()

    expect(wrapper.classes()).toContain('yp-label-popover-root--select')
    expect(contentsApi.createContent).not.toHaveBeenCalled()
  })
})
