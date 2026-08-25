import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const migration = read('backend/src/main/resources/db/migration/filestorage/V37__create_attachment_upload_processing.sql')
const repository = read('backend/src/main/java/com/yumpoo/platform/filestorage/infrastructure/JdbcAttachmentRepository.java')
const lifecycle = read('backend/src/main/java/com/yumpoo/platform/filestorage/application/AttachmentLifecycleService.java')
const controller = read('backend/src/main/java/com/yumpoo/platform/administration/api/AttachmentController.java')
const finalizer = read('backend/src/main/java/com/yumpoo/platform/administration/application/AttachmentFinalizationService.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const sdk = read('packages/api-client/src/generated/apis/AttachmentsApi.ts')
const eventSchema = read('contracts/events/schemas/filestorage.attachment-available-v1.schema.json')
const panel = read('frontend/web-app/src/components/collaboration/AttachmentPanel.vue')
const panelTest = read('frontend/web-app/src/components/collaboration/AttachmentPanel.spec.ts')
const discussion = read('frontend/web-app/src/components/collaboration/WorkItemDiscussion.vue')
const migrationTest = read('backend/src/test/java/com/yumpoo/platform/YumpooServerApplicationIT.java')
const backup = read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const note = read('.agents/notes/implemented/architecture/2026-08-25-attachment-upload-processing.md')
const readme = read('README.md')
const acceptance = JSON.parse(read('evidence/m2-18/acceptance-matrix.json'))
const report = JSON.parse(read('evidence/m2-18/verification-report.json'))

for (const fragment of ['CREATE TABLE yumpoo.attachment (', 'CREATE TABLE yumpoo.attachment_scan_task',
  'CREATE TABLE yumpoo.attachment_quota_usage', 'QUOTA_EXCEEDED',
  "status IN ('UPLOADING', 'AVAILABLE', 'REJECTED', 'DELETED')", 'ck_attachment_available']) {
  assert(migration.includes(fragment), `V37 缺少 ${fragment}`)
}
for (const fragment of ['ORDER BY scope_type', 'FOR UPDATE SKIP LOCKED', 'attempt_count=attempt_count+1',
  "status='RUNNING'", 'quarantine_retain_until', 'completeAvailable', 'rescan(']) {
  assert(repository.includes(fragment), `JDBC 闭环缺少 ${fragment}`)
}
for (const fragment of ['AttachmentUploadPolicy.MAX_BYTES', 'settings.firstScanRetry()',
  'settings.secondScanRetry()', 'repository.recordDetected', 'storage.publish(upload)']) {
  assert(lifecycle.includes(fragment), `生命周期缺少 ${fragment}`)
}
for (const fragment of ['@PostMapping("/attachments")', '@PutMapping(path="/attachments/{attachmentId}/content"',
  '@GetMapping("/attachments/{attachmentId}")', '@GetMapping("/work-items/{workItemId}/attachments")',
  '@GetMapping("/work-item-updates/{updateId}/attachments")',
  '@PostMapping("/admin/attachments/{attachmentId}/rescan")']) {
  assert(controller.includes(fragment), `HTTP API 缺少 ${fragment}`)
}
for (const forbidden of ['sha256', 'storageKey', 'processingStage', 'scannerOutput', 'bodyHtml', 'bodyText']) {
  assert(!eventSchema.includes(`"${forbidden}"`), `AVAILABLE 事件泄漏 ${forbidden}`)
}
assert(finalizer.includes('filestorage.attachment_available'), '最终化未发布 AVAILABLE 事件')
for (const fragment of ['AttachmentOwnerType:', 'PRODUCT_FEEDBACK', 'AttachmentRejectedCode:',
  '/admin/attachments/{attachmentId}/rescan', 'canUploadContent', 'maxBytes']) {
  assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
}
for (const fragment of ['createAttachmentIntent', 'uploadAttachmentContent', 'getAttachment',
  'listWorkItemAttachments', 'listWorkItemUpdateAttachments', 'rescanAttachment']) {
  assert(sdk.includes(fragment), `生成 SDK 缺少 ${fragment}`)
}
for (const fragment of ['setTimeout(tick, 1000)', 'const delays = [1000, 2000, 5000]',
  'metadata.capabilities.canUploadContent', 'putWithRecovery', 'onBeforeUnmount(stopAll)']) {
  assert(panel.includes(fragment), `Web 上传闭环缺少 ${fragment}`)
}
assert(discussion.includes('expandedAttachmentIds.has(item.id)'), 'Update 附件未按需展开')
for (const fragment of ['PUT 结果未知时查询 metadata 并复用原意图重试', '按 owner 选择列表接口']) {
  assert(panelTest.includes(fragment), `Web 验收缺少 ${fragment}`)
}
for (const fragment of ['"37"', 'v36DatabaseUpgradesForwardThroughV37WithoutRewritingPublishedMigration']) {
  assert(migrationTest.includes(fragment), `V37 迁移验收缺少 ${fragment}`)
}
for (const fragment of ['createAttachmentFact', 'readAttachmentFact', 'attachment_quota_usage',
  'attachment_scan_task']) assert(backup.includes(fragment), `备份恢复缺少 ${fragment}`)
for (const fragment of ['持久队列', '固定顺序锁定', '安全扫描已通过', 'M2-19', 'M3B']) {
  assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
}
assert(readme.includes('## M2-18 附件上传与安全扫描闭环'), 'README 未同步 M2-18')
assert(report.milestone === 'M2-18' && report.flywayVersion === '37', '验证报告无效')
for (const requirement of ['ATTACHMENT-INTENT-UPLOAD-QUOTA', 'ATTACHMENT-PERSISTENT-SCAN',
  'ATTACHMENT-API-RESCAN', 'ATTACHMENT-WEB', 'ATTACHMENT-MIGRATION-BACKUP']) {
  assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement), `验收矩阵缺少 ${requirement}`)
}
assert(acceptance.deferredRequirements.some(item => item.requirementId === 'ATTACHMENT-DOWNLOAD-DELETE-CLEANUP'
  && item.targetMilestone === 'M2-19'), 'M2-19 延期边界无效')
assert(acceptance.deferredRequirements.some(item => item.requirementId === 'FEEDBACK-ATTACHMENTS'
  && item.targetMilestone === 'M3B'), 'Feedback 延期边界无效')

console.log('M2-18 数据、扫描、API、Web、迁移备份与证据资产有效。')
function assert(condition, message) {
  if (!condition) throw new Error(`M2-18 资产验证失败：${message}`)
}
