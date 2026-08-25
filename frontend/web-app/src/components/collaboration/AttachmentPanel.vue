<script setup lang="ts">
import {
  AttachmentOwnerType,
  AttachmentStatus,
  readCsrfToken,
  type AttachmentMetadata,
} from '@yumpoo/api-client'
import { ElButton, ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from 'vue'
import { attachmentsApi } from '../../api/client'
import { problemMessage, toApiProblem } from '../../api/problems'

const props = defineProps<{
  ownerType: AttachmentOwnerType.WorkItem | AttachmentOwnerType.WorkItemUpdate
  ownerId: string
  canUpload: boolean
}>()

const items = ref<AttachmentMetadata[]>([])
const loading = ref(false)
const uploading = ref(false)
const error = ref<string>()
const receivingIds = ref(new Set<string>())
const deletingIds = ref(new Set<string>())
const polling = new Map<string, ReturnType<typeof setTimeout>>()
let generation = 0

const empty = computed(() => !loading.value && items.value.length === 0)

function statusLabel(item: AttachmentMetadata) {
  if (receivingIds.value.has(item.id)) return '接收中'
  if (item.status === AttachmentStatus.Available) return '可用'
  if (item.status === AttachmentStatus.Rejected) return rejectedLabel(item.rejectedCode)
  if (item.capabilities.canUploadContent) return '待上传'
  return '安全扫描中'
}

function rejectedLabel(code?: string | null) {
  const labels: Record<string, string> = {
    FILE_TOO_LARGE: '文件超过 100 MiB',
    FILE_TYPE_NOT_ALLOWED: '文件类型不允许',
    MALWARE_DETECTED: '文件未通过安全检查',
    SCAN_UNAVAILABLE: '安全扫描暂不可用',
    UPLOAD_INCOMPLETE: '上传不完整',
    INTEGRITY_CHECK_FAILED: '完整性检查失败',
    PARENT_NOT_WRITABLE: '父对象已不可写',
    QUOTA_EXCEEDED: '附件配额不足',
  }
  return code ? labels[code] ?? '附件被拒绝' : '附件被拒绝'
}

async function load() {
  const current = ++generation
  loading.value = true
  error.value = undefined
  try {
    const page = props.ownerType === AttachmentOwnerType.WorkItem
      ? await attachmentsApi.listWorkItemAttachments({ workItemId: props.ownerId, size: 100 })
      : await attachmentsApi.listWorkItemUpdateAttachments({ updateId: props.ownerId, size: 100 })
    if (current === generation) items.value = page.items
    return current === generation
  } catch (reason) {
    if (current === generation) error.value = problemMessage(await toApiProblem(reason))
    return false
  } finally {
    if (current === generation) loading.value = false
  }
}

async function choose(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (file.size > 104857600) {
    ElMessage.error('单个附件不能超过 100 MiB。')
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    ElMessage.error('安全凭据已失效，请刷新页面后重试。')
    return
  }
  uploading.value = true
  error.value = undefined
  try {
    const intent = await attachmentsApi.createAttachmentIntent({
      xXSRFTOKEN: csrf,
      idempotencyKey: crypto.randomUUID(),
      attachmentIntentCreateRequest: {
        ownerType: props.ownerType,
        ownerId: props.ownerId,
        originalFileName: file.name,
        declaredMime: file.type || 'application/octet-stream',
        sizeBytes: file.size,
      },
    })
    upsert(intent.metadata)
    await putWithRecovery(intent.metadata.id, file, csrf)
  } catch (reason) {
    error.value = problemMessage(await toApiProblem(reason))
  } finally {
    uploading.value = false
  }
}

async function putWithRecovery(attachmentId: string, file: Blob, csrf: string) {
  const receiving = new Set(receivingIds.value)
  receiving.add(attachmentId)
  receivingIds.value = receiving
  try {
    const metadata = await attachmentsApi.uploadAttachmentContent({
      attachmentId,
      xXSRFTOKEN: csrf,
      body: file,
    })
    upsert(metadata)
    startPolling(metadata.id)
  } catch (reason) {
    try {
      const problem = await toApiProblem(reason)
      if (problem.kind === 'response') throw reason
      const metadata = await attachmentsApi.getAttachment({ attachmentId })
      upsert(metadata)
      if (metadata.capabilities.canUploadContent) {
        const retried = await attachmentsApi.uploadAttachmentContent({ attachmentId, xXSRFTOKEN: csrf, body: file })
        upsert(retried)
      }
      startPolling(attachmentId)
    } finally {
      const next = new Set(receivingIds.value)
      next.delete(attachmentId)
      receivingIds.value = next
    }
    return
  } finally {
    const next = new Set(receivingIds.value)
    next.delete(attachmentId)
    receivingIds.value = next
  }
}

function startPolling(attachmentId: string) {
  stopPolling(attachmentId)
  const started = Date.now()
  let attempt = 1
  const tick = async () => {
    try {
      const metadata = await attachmentsApi.getAttachment({ attachmentId })
      upsert(metadata)
      if (metadata.status !== AttachmentStatus.Uploading || Date.now() - started >= 300000) return
    } catch {
      if (Date.now() - started >= 300000) return
    }
    const delays = [1000, 2000, 5000]
    const delay = delays[Math.min(attempt++, delays.length - 1)] ?? 5000
    polling.set(attachmentId, setTimeout(tick, delay))
  }
  polling.set(attachmentId, setTimeout(tick, 1000))
}

function upsert(metadata: AttachmentMetadata) {
  const index = items.value.findIndex(item => item.id === metadata.id)
  if (index < 0) items.value = [metadata, ...items.value]
  else items.value.splice(index, 1, metadata)
}

function stopPolling(id: string) {
  const timer = polling.get(id)
  if (timer) clearTimeout(timer)
  polling.delete(id)
}

function stopAll() {
  generation++
  for (const timer of polling.values()) clearTimeout(timer)
  polling.clear()
}

function downloadUrl(item: AttachmentMetadata) {
  return `/api/v1/attachments/${encodeURIComponent(item.id)}/content`
}

async function confirmDelete(item: AttachmentMetadata) {
  let reason: string
  try {
    const result = await ElMessageBox.prompt('请输入附件删除理由（1–500 字）', '删除附件', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      inputType: 'textarea',
      inputValidator: value => {
        const normalized = value.trim()
        return normalized.length > 0 && normalized.length <= 500 ? true : '理由须为 1–500 字'
      },
    })
    reason = result.value.trim()
  } catch {
    return
  }
  const csrf = readCsrfToken()
  if (!csrf) {
    ElMessage.error('安全凭据已失效，请刷新页面后重试。')
    return
  }
  const key = crypto.randomUUID()
  const deleting = new Set(deletingIds.value)
  deleting.add(item.id)
  deletingIds.value = deleting
  try {
    await deleteWithRecovery(item, reason, key, csrf)
  } finally {
    const next = new Set(deletingIds.value)
    next.delete(item.id)
    deletingIds.value = next
  }
}

async function deleteWithRecovery(item: AttachmentMetadata, reason: string, key: string, csrf: string) {
  const request = {
    attachmentId: item.id,
    xXSRFTOKEN: csrf,
    idempotencyKey: key,
    ifMatch: item.etag,
    attachmentDeleteRequest: { reason },
  }
  try {
    await attachmentsApi.deleteAttachment(request)
    remove(item.id)
    ElMessage.success('附件已删除')
    return
  } catch (failure) {
    const problem = await toApiProblem(failure)
    if (problem.kind === 'response') {
      if (problem.status === 409 || problem.status === 412) await load()
      error.value = problemMessage(problem)
      return
    }
    const refreshed = await load()
    if (!refreshed) return
    if (!items.value.some(current => current.id === item.id)) return
    try {
      await attachmentsApi.deleteAttachment(request)
      remove(item.id)
      ElMessage.success('附件已删除')
    } catch (retryFailure) {
      const retryProblem = await toApiProblem(retryFailure)
      if (retryProblem.kind === 'response' && (retryProblem.status === 409 || retryProblem.status === 412)) {
        await load()
      }
      error.value = problemMessage(retryProblem)
    }
  }
}

function remove(id: string) {
  stopPolling(id)
  items.value = items.value.filter(item => item.id !== id)
}

watch(() => [props.ownerType, props.ownerId], () => { stopAll(); void load() })
onMounted(() => void load())
onBeforeUnmount(stopAll)
onDeactivated(stopAll)
</script>

<template>
  <section class="attachment-panel" aria-label="附件">
    <header>
      <h4>附件</h4>
      <span class="attachment-panel__actions">
        <label v-if="canUpload" class="attachment-picker" :aria-disabled="uploading">
          <input type="file" :disabled="uploading" @change="choose">
          {{ uploading ? '上传中…' : '添加附件' }}
        </label>
        <el-button text :loading="loading" @click="load">刷新</el-button>
      </span>
    </header>
    <p v-if="error" class="attachment-panel__error">{{ error }}</p>
    <p v-if="empty" class="attachment-panel__empty">暂无附件</p>
    <ul v-else class="attachment-list">
      <li v-for="item in items" :key="item.id">
        <a v-if="item.capabilities.canDownloadContent" class="attachment-name attachment-name--download"
          :href="downloadUrl(item)">{{ item.originalFileName }}</a>
        <span v-else class="attachment-name">{{ item.originalFileName }}</span>
        <span class="attachment-size">{{ item.sizeBytes == null ? '—' : `${Math.ceil(item.sizeBytes / 1024)} KiB` }}</span>
        <span :class="['attachment-status', `attachment-status--${item.status.toLowerCase()}`]">{{ statusLabel(item) }}</span>
        <el-button v-if="item.capabilities.canDelete" text type="danger"
          :loading="deletingIds.has(item.id)" @click="confirmDelete(item)">删除</el-button>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.attachment-panel { display: grid; gap: var(--yp-space-2); padding: var(--yp-space-3); border: 1px solid var(--yp-border-subtle); border-radius: var(--yp-radius-md); background: var(--yp-bg-surface); }
.attachment-panel header { display: flex; align-items: center; justify-content: space-between; gap: var(--yp-space-2); }
.attachment-panel h4 { margin: 0; font-size: var(--yp-type-body-size); }
.attachment-panel__actions { display: flex; align-items: center; gap: var(--yp-space-2); }
.attachment-picker { display: inline-flex; min-height: 32px; align-items: center; padding: 0 var(--yp-space-3); border-radius: var(--yp-radius-sm); color: white; background: var(--yp-action-primary); cursor: pointer; }
.attachment-picker input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.attachment-list { display: grid; gap: var(--yp-space-2); margin: 0; padding: 0; list-style: none; }
.attachment-list li { display: grid; grid-template-columns: minmax(0, 1fr) auto auto auto; align-items: center; gap: var(--yp-space-3); padding: var(--yp-space-2); border-radius: var(--yp-radius-sm); background: var(--yp-bg-sunken); }
.attachment-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attachment-name--download { color: var(--yp-action-primary); text-decoration: none; }
.attachment-name--download:hover { text-decoration: underline; }
.attachment-size, .attachment-status, .attachment-panel__empty { color: var(--yp-text-muted); font-size: var(--yp-type-caption-size); }
.attachment-status--available { color: var(--yp-status-green); }
.attachment-status--rejected, .attachment-panel__error { color: var(--yp-status-red); }
</style>
