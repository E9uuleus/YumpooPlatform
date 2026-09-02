import {
  WorkItemLabelColorToken,
  type Content,
  type ProjectContentCatalog,
} from '@yumpoo/api-client'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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
        colorToken: WorkItemLabelColorToken.BrightGreen, sortOrder: 20 }),
      content({ id: 'content-defect', code: 'DEFECTS', name: '缺陷',
        colorToken: WorkItemLabelColorToken.DarkRed, sortOrder: 30, active: false }),
    ],
  }
}

describe('WorkItemContentPopoverContent', () => {
  beforeEach(() => vi.clearAllMocks())

  it('选择态只显示启用类别和当前停用类别，并以圆角长条选择', async () => {
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: catalog(), currentValue: 'content-defect', canManage: false },
    })

    expect(wrapper.findAll('.content-pill')).toHaveLength(3)
    expect(wrapper.find('.content-option--inactive').text()).toContain('缺陷')
    expect(wrapper.find('.content-manage').exists()).toBe(false)
    expect(wrapper.find('.content-pill').attributes('style')).toContain('--yp-label-bright-blue')

    await wrapper.findAll('.content-option')[1]?.trigger('click')
    expect(wrapper.emitted('select')?.[0]).toEqual(['content-task'])
  })

  it('非当前停用类别不可供选择', () => {
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: catalog(), currentValue: 'content-default', canManage: false },
    })
    expect(wrapper.findAll('.content-pill').map(node => node.text())).toEqual(['需求', '任务'])
  })

  it('Owner 可进入管理态，受保护或曾使用类别不可删除，并可新增类别', async () => {
    const wrapper = mount(WorkItemContentPopoverContent, {
      props: { projectId: 'project-1', catalog: catalog(), canManage: true },
    })

    await wrapper.find('.content-manage').trigger('click')
    expect(wrapper.findAll('.content-editor-row')).toHaveLength(3)
    expect(wrapper.findAll('.content-delete').every(button => button.attributes('disabled') !== undefined)).toBe(true)

    await wrapper.find('.content-editor-actions button').trigger('click')
    expect(wrapper.findAll('.content-editor-row')).toHaveLength(4)
    expect(wrapper.findAll('.content-delete').at(-1)?.attributes('disabled')).toBeUndefined()
  })
})
