import {
  AttachmentOwnerType,
  AttachmentStatus,
  type AttachmentMetadata,
} from '@yumpoo/api-client'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AttachmentPanel from './AttachmentPanel.vue'

const api = vi.hoisted(() => ({
  listWorkItems: vi.fn(),
  listUpdates: vi.fn(),
  create: vi.fn(),
  upload: vi.fn(),
  get: vi.fn(),
}))

vi.mock('../../api/client', () => ({
  attachmentsApi: {
    listWorkItemAttachments: api.listWorkItems,
    listWorkItemUpdateAttachments: api.listUpdates,
    createAttachmentIntent: api.create,
    uploadAttachmentContent: api.upload,
    getAttachment: api.get,
  },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: () => 'csrf-token',
}))

const attachmentId = '37000000-0000-4000-8000-000000000001'
const ownerId = '37000000-0000-4000-8000-000000000002'

function metadata(canUploadContent = true): AttachmentMetadata {
  return {
    id: attachmentId,
    companyId: '37000000-0000-4000-8000-000000000003',
    projectId: '37000000-0000-4000-8000-000000000004',
    ownerType: AttachmentOwnerType.WorkItem,
    ownerId,
    originalFileName: 'evidence.txt',
    declaredMime: 'text/plain',
    status: AttachmentStatus.Uploading,
    uploadedByUserId: '37000000-0000-4000-8000-000000000005',
    createdAt: new Date('2026-08-25T01:00:00Z'),
    expiresAt: new Date('2026-08-26T01:00:00Z'),
    rowVersion: 0,
    etag: '"0"',
    capabilities: { canUploadContent },
  }
}

describe('AttachmentPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listWorkItems.mockResolvedValue({ items: [], nextCursor: null })
    api.listUpdates.mockResolvedValue({ items: [], nextCursor: null })
  })

  it('按 owner 选择列表接口，并在只读状态隐藏上传入口', async () => {
    const wrapper = mount(AttachmentPanel, {
      props: { ownerType: AttachmentOwnerType.WorkItemUpdate, ownerId, canUpload: false },
    })
    await flushPromises()

    expect(api.listUpdates).toHaveBeenCalledWith({ updateId: ownerId, size: 100 })
    expect(api.listWorkItems).not.toHaveBeenCalled()
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('PUT 结果未知时查询 metadata 并复用原意图重试', async () => {
    const intent = metadata(true)
    api.create.mockResolvedValue({
      uploadUrl: `/api/v1/attachments/${attachmentId}/content`,
      expiresAt: intent.expiresAt,
      maxBytes: 104857600,
      metadata: intent,
    })
    api.upload.mockRejectedValueOnce(new TypeError('network unavailable'))
      .mockResolvedValueOnce(metadata(false))
    api.get.mockResolvedValue(metadata(true))
    const wrapper = mount(AttachmentPanel, {
      props: { ownerType: AttachmentOwnerType.WorkItem, ownerId, canUpload: true },
    })
    await flushPromises()

    const file = new File(['safe'], 'evidence.txt', { type: 'text/plain' })
    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()

    expect(api.create).toHaveBeenCalledOnce()
    expect(api.get).toHaveBeenCalledWith({ attachmentId })
    expect(api.upload).toHaveBeenCalledTimes(2)
    expect(api.upload.mock.calls[0]?.[0].attachmentId).toBe(attachmentId)
    expect(api.upload.mock.calls[1]?.[0].attachmentId).toBe(attachmentId)
    wrapper.unmount()
  })
})
