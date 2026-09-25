import { AttachmentOwnerType, AttachmentRejectedCode, AttachmentStatus, type AttachmentMetadata } from '@yumpoo/api-client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AttachmentUploadError, attachmentContentUrl, isUploadAborted, uploadAttachment } from './attachmentUpload'

const api = vi.hoisted(() => ({ create: vi.fn(), upload: vi.fn(), get: vi.fn(), csrf: vi.fn(() => 'csrf-token') }))

vi.mock('../../api/client', () => ({
  attachmentsApi: {
    createAttachmentIntent: api.create,
    uploadAttachmentContent: api.upload,
    getAttachment: api.get,
  },
}))
vi.mock('@yumpoo/api-client', async importOriginal => ({
  ...await importOriginal<typeof import('@yumpoo/api-client')>(),
  readCsrfToken: api.csrf,
}))
vi.mock('../../api/problems', async importOriginal => ({
  ...await importOriginal<typeof import('../../api/problems')>(),
  toApiProblem: async (reason: unknown) => reason,
}))

const attachmentId = '38000000-0000-4000-8000-000000000001'
const ownerId = '38000000-0000-4000-8000-000000000002'
const file = new File(['png'], '截图.png', { type: 'image/png' })

function metadata(status: AttachmentStatus, patch: Partial<AttachmentMetadata> = {}): AttachmentMetadata {
  return {
    id: attachmentId,
    companyId: '38000000-0000-4000-8000-000000000003',
    projectId: '38000000-0000-4000-8000-000000000004',
    ownerType: AttachmentOwnerType.WorkItem,
    ownerId,
    originalFileName: '截图.png',
    declaredMime: 'image/png',
    status,
    uploadedByUserId: '38000000-0000-4000-8000-000000000005',
    createdAt: new Date('2026-09-25T01:00:00Z'),
    expiresAt: new Date('2026-09-26T01:00:00Z'),
    rowVersion: 0,
    etag: '"0"',
    capabilities: { canUploadContent: status === AttachmentStatus.Uploading, canDownloadContent: status === AttachmentStatus.Available, canDelete: false },
    ...patch,
  }
}

describe('uploadAttachment', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    api.csrf.mockReturnValue('csrf-token')
    api.create.mockResolvedValue({ metadata: metadata(AttachmentStatus.Uploading) })
    api.upload.mockResolvedValue(metadata(AttachmentStatus.Uploading, { capabilities: { canUploadContent: false, canDownloadContent: false, canDelete: false } }))
  })
  afterEach(() => { vi.useRealTimers() })

  it('创建意图、上传内容并轮询到扫描通过', async () => {
    api.get.mockResolvedValueOnce(metadata(AttachmentStatus.Uploading)).mockResolvedValueOnce(metadata(AttachmentStatus.Available))
    const phases: string[] = []
    const result = uploadAttachment({ ownerType: AttachmentOwnerType.WorkItem, ownerId, file, onPhase: phase => phases.push(phase) })
    await vi.advanceTimersByTimeAsync(3000)

    await expect(result).resolves.toMatchObject({ id: attachmentId, status: AttachmentStatus.Available })
    expect(api.create).toHaveBeenCalledWith(expect.objectContaining({
      xXSRFTOKEN: 'csrf-token',
      attachmentIntentCreateRequest: { ownerType: AttachmentOwnerType.WorkItem, ownerId, originalFileName: '截图.png', declaredMime: 'image/png', sizeBytes: 3 },
    }), expect.anything())
    expect(api.upload).toHaveBeenCalledWith({ attachmentId, xXSRFTOKEN: 'csrf-token', body: file }, expect.anything())
    expect(phases).toEqual(['uploading', 'scanning'])
    expect(attachmentContentUrl(attachmentId)).toBe(`/api/v1/attachments/${attachmentId}/content`)
  })

  it('网络中断后读取意图并仅重试一次上传', async () => {
    api.upload.mockRejectedValueOnce({ kind: 'fallback', message: '网络异常' })
    api.get.mockResolvedValueOnce(metadata(AttachmentStatus.Uploading)).mockResolvedValue(metadata(AttachmentStatus.Available))
    const result = uploadAttachment({ ownerType: AttachmentOwnerType.WorkItem, ownerId, file })
    await vi.advanceTimersByTimeAsync(2000)

    await expect(result).resolves.toMatchObject({ status: AttachmentStatus.Available })
    expect(api.upload).toHaveBeenCalledTimes(2)
  })

  it('扫描拒绝与超时转换为中文错误', async () => {
    api.get.mockResolvedValue(metadata(AttachmentStatus.Rejected, { rejectedCode: AttachmentRejectedCode.MalwareDetected }))
    const rejected = uploadAttachment({ ownerType: AttachmentOwnerType.WorkItem, ownerId, file })
    const rejectedAssertion = expect(rejected).rejects.toThrow('文件未通过安全检查')
    await vi.advanceTimersByTimeAsync(1000)
    await rejectedAssertion

    api.get.mockResolvedValue(metadata(AttachmentStatus.Uploading))
    const timedOut = uploadAttachment({ ownerType: AttachmentOwnerType.WorkItem, ownerId, file })
    const timeoutAssertion = expect(timedOut).rejects.toBeInstanceOf(AttachmentUploadError)
    await vi.advanceTimersByTimeAsync(310_000)
    await timeoutAssertion
  })

  it('取消会停止轮询且不再请求', async () => {
    api.get.mockResolvedValue(metadata(AttachmentStatus.Uploading))
    const controller = new AbortController()
    const result = uploadAttachment({ ownerType: AttachmentOwnerType.WorkItem, ownerId, file, signal: controller.signal })
    const assertion = expect(result).rejects.toSatisfy(isUploadAborted)
    await vi.advanceTimersByTimeAsync(1000)
    controller.abort()
    await assertion
    const calls = api.get.mock.calls.length
    await vi.advanceTimersByTimeAsync(10_000)
    expect(api.get).toHaveBeenCalledTimes(calls)
  })

  it('缺少 CSRF 凭据时不创建意图', async () => {
    api.csrf.mockReturnValue(undefined as never)
    await expect(uploadAttachment({ ownerType: AttachmentOwnerType.WorkItem, ownerId, file })).rejects.toThrow('安全凭据已失效')
    expect(api.create).not.toHaveBeenCalled()
  })
})
