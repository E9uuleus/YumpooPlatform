import { AttachmentOwnerType } from '@yumpoo/api-client'
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LazyAttachmentPanel from './LazyAttachmentPanel.vue'

describe('LazyAttachmentPanel', () => {
  it('只在用户展开附件区域后挂载查询组件', async () => {
    const wrapper = mount(LazyAttachmentPanel, {
      props: {
        ownerType: AttachmentOwnerType.WorkItem,
        ownerId: 'work-item-1',
        canUpload: true,
      },
      global: {
        stubs: { AttachmentPanel: { template: '<div data-testid="attachment-panel" />' } },
      },
    })

    expect(wrapper.find('[data-testid="attachment-panel"]').exists()).toBe(false)
    await wrapper.get('.lazy-attachments__toggle').trigger('click')
    expect(wrapper.find('[data-testid="attachment-panel"]').exists()).toBe(true)
  })
})
