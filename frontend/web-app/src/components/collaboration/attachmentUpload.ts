import {
  AttachmentStatus,
  readCsrfToken,
  type AttachmentMetadata,
  type AttachmentOwnerType,
} from '@yumpoo/api-client'
import { attachmentsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'

const scanTimeoutMs = 300_000
const pollDelaysMs = [1000, 2000, 5000]
const rejectedLabels: Record<string, string> = {
  FILE_TOO_LARGE: '文件超过 100 MiB',
  FILE_TYPE_NOT_ALLOWED: '文件类型不允许',
  MALWARE_DETECTED: '文件未通过安全检查',
  SCAN_UNAVAILABLE: '安全扫描暂不可用',
  UPLOAD_INCOMPLETE: '上传不完整',
  INTEGRITY_CHECK_FAILED: '完整性检查失败',
  PARENT_NOT_WRITABLE: '父对象已不可写',
  QUOTA_EXCEEDED: '附件配额不足',
}

export type AttachmentUploadPhase = 'uploading' | 'scanning'

export class AttachmentUploadError extends Error {}

export function attachmentContentUrl(attachmentId: string): string {
  return `/api/v1/attachments/${encodeURIComponent(attachmentId)}/content`
}

export function attachmentRejectedLabel(code?: string | null): string {
  return code ? rejectedLabels[code] ?? '附件被拒绝' : '附件被拒绝'
}

export function isUploadAborted(reason: unknown): boolean {
  return reason instanceof DOMException && reason.name === 'AbortError'
}

function abortError(): DOMException { return new DOMException('上传已取消', 'AbortError') }

function wait(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) { reject(abortError()); return }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', cancel); resolve() }, ms)
    const cancel = () => { clearTimeout(timer); reject(abortError()) }
    signal?.addEventListener('abort', cancel, { once: true })
  })
}

async function failure(reason: unknown, signal?: AbortSignal): Promise<Error> {
  if (signal?.aborted) return abortError()
  if (reason instanceof AttachmentUploadError) return reason
  return new AttachmentUploadError(problemMessage(await toApiProblem(reason)))
}

/**
 * Runs the two-step attachment protocol (intent → PUT → scan polling) for one file and resolves with
 * the AVAILABLE metadata. Transport failures during PUT re-read the intent before a single retry,
 * matching the attachment panel's recovery rules; abort stops polling without deleting the intent.
 */
export async function uploadAttachment(options: {
  ownerType: AttachmentOwnerType
  ownerId: string
  file: File
  signal?: AbortSignal
  onPhase?: (phase: AttachmentUploadPhase) => void
}): Promise<AttachmentMetadata> {
  const { file, signal } = options
  const init = { signal: signal ?? null }
  const csrf = readCsrfToken()
  if (!csrf) throw new AttachmentUploadError('安全凭据已失效，请刷新页面后重试。')
  try {
    options.onPhase?.('uploading')
    const intent = await attachmentsApi.createAttachmentIntent({
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      attachmentIntentCreateRequest: {
        ownerType: options.ownerType,
        ownerId: options.ownerId,
        originalFileName: file.name,
        declaredMime: file.type || 'application/octet-stream',
        sizeBytes: file.size,
      },
    }, init)
    const attachmentId = intent.metadata.id
    try {
      await attachmentsApi.uploadAttachmentContent({ attachmentId, xXSRFTOKEN: csrf, body: file }, init)
    } catch (reason) {
      if (signal?.aborted || (await toApiProblem(reason)).kind === 'response') throw reason
      const current = await attachmentsApi.getAttachment({ attachmentId }, init)
      if (current.capabilities.canUploadContent) {
        await attachmentsApi.uploadAttachmentContent({ attachmentId, xXSRFTOKEN: csrf, body: file }, init)
      }
    }
    options.onPhase?.('scanning')
    const started = Date.now()
    for (let attempt = 0; ; attempt++) {
      await wait(pollDelaysMs[Math.min(attempt, pollDelaysMs.length - 1)] ?? 5000, signal)
      let metadata: AttachmentMetadata | undefined
      try { metadata = await attachmentsApi.getAttachment({ attachmentId }, init) }
      catch (reason) { if (signal?.aborted) throw reason }
      if (metadata?.status === AttachmentStatus.Available) return metadata
      if (metadata && metadata.status !== AttachmentStatus.Uploading) {
        throw new AttachmentUploadError(attachmentRejectedLabel(metadata.rejectedCode))
      }
      if (Date.now() - started >= scanTimeoutMs) throw new AttachmentUploadError('安全扫描超时，请稍后重试。')
    }
  } catch (reason) {
    throw await failure(reason, signal)
  }
}
