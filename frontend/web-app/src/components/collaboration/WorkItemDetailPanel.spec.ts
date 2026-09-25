import type { WorkItemDetail } from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessageBox } from 'element-plus'
import { defineComponent, reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkItemDetailPanel from './WorkItemDetailPanel.vue'

const handles = vi.hoisted(() => ({
  description: { hasDraft: false, busy: false, discardDraft: vi.fn() },
  discussion: { hasDraft: false, busy: false, discardDraft: vi.fn() },
}))

function stub(name: string, handle: object) {
  return defineComponent({ name, setup(_props, { expose }) { expose(handle); return {} }, template: '<div />' })
}

const detail = {
  id: '3a000000-0000-4000-8000-000000000001', projectId: '3a000000-0000-4000-8000-000000000002',
  description: '<p>描述</p>', etag: '"3"', capabilities: { canEditFields: true },
} as WorkItemDetail

function mountPanel(tab: 'details' | 'discussion') {
  return mount(WorkItemDetailPanel, {
    props: { modelValue: tab, detail, members: [], canPublish: true },
    global: {
      stubs: {
        WorkItemDescription: stub('WorkItemDescription', handles.description),
        WorkItemDiscussion: stub('WorkItemDiscussion', handles.discussion),
        WorkItemRelations: true,
        WorkItemCellActivityLog: true,
      },
    },
  })
}

describe('WorkItemDetailPanel', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    handles.description = reactive({ hasDraft: false, busy: false, discardDraft: vi.fn() })
    handles.discussion = reactive({ hasDraft: false, busy: false, discardDraft: vi.fn() })
  })

  it('详情页传入描述真源，离开详情标签不丢弃常驻的描述草稿', async () => {
    handles.description.hasDraft = true
    const wrapper = mountPanel('details')
    await flushPromises()
    const description = wrapper.findComponent({ name: 'WorkItemDescription' })
    expect(description.attributes()).toMatchObject({ description: '<p>描述</p>', etag: '"3"', 'can-edit': 'true' })
    const confirm = vi.spyOn(ElMessageBox, 'confirm')
    const guard = wrapper.findComponent({ name: 'ElTabs' }).props('beforeLeave') as (next: string, previous: string) => Promise<boolean>

    expect(await guard('discussion', 'details')).toBe(true)
    expect(confirm).not.toHaveBeenCalled()
    expect(handles.description.discardDraft).not.toHaveBeenCalled()
    expect((wrapper.vm as unknown as { hasDraft: boolean }).hasDraft).toBe(true)
  })

  it('离开讨论标签只确认讨论草稿，关闭时汇总丢弃全部草稿', async () => {
    handles.discussion.hasDraft = true
    const wrapper = mountPanel('discussion')
    await flushPromises()
    const guard = wrapper.findComponent({ name: 'ElTabs' }).props('beforeLeave') as (next: string, previous: string) => Promise<boolean>
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockRejectedValueOnce('cancel').mockResolvedValueOnce('confirm' as never)

    expect(await guard('details', 'discussion')).toBe(false)
    expect(await guard('details', 'discussion')).toBe(true)
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(handles.discussion.discardDraft).toHaveBeenCalledOnce()
    expect(handles.description.discardDraft).not.toHaveBeenCalled()

    ;(wrapper.vm as unknown as { discardDraft: () => void }).discardDraft()
    expect(handles.description.discardDraft).toHaveBeenCalledOnce()
  })
})
