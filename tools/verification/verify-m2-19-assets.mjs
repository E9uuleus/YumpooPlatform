import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const migration = read('backend/src/main/resources/db/migration/filestorage/V38__add_attachment_download_cleanup.sql')
const controller = read('backend/src/main/java/com/yumpoo/platform/administration/api/AttachmentController.java')
const application = read('backend/src/main/java/com/yumpoo/platform/administration/application/AttachmentApplicationService.java')
const lifecycle = read('backend/src/main/java/com/yumpoo/platform/filestorage/application/AttachmentLifecycleService.java')
const repository = read('backend/src/main/java/com/yumpoo/platform/filestorage/infrastructure/JdbcAttachmentRepository.java')
const maintenance = read('backend/src/main/java/com/yumpoo/platform/filestorage/infrastructure/JdbcAttachmentMaintenanceService.java')
const storage = read('backend/src/main/java/com/yumpoo/platform/filestorage/infrastructure/LocalFileQuarantineStorage.java')
const properties = read('backend/src/main/java/com/yumpoo/platform/filestorage/infrastructure/AttachmentProperties.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const sdk = read('packages/api-client/src/generated/apis/AttachmentsApi.ts')
const eventSchema = read('contracts/events/schemas/filestorage.attachment-deleted-v1.schema.json')
const panel = read('frontend/web-app/src/components/collaboration/AttachmentPanel.vue')
const panelTest = read('frontend/web-app/src/components/collaboration/AttachmentPanel.spec.ts')
const migrationTest = read('backend/src/test/java/com/yumpoo/platform/YumpooServerApplicationIT.java')
const backup = read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const backupReportSchema = read('evidence/m0-17/verification-report.schema.json')
const backupReportExample = read('evidence/m0-17/verification-report.example.json')
const note = read('.agents/notes/implemented/architecture/2026-08-25-attachment-upload-processing.md')
const readme = read('README.md')
const acceptance = JSON.parse(read('evidence/m2-19/acceptance-matrix.json'))
const report = JSON.parse(read('evidence/m2-19/verification-report.json'))

for (const fragment of ['deleted_by_user_id', 'ck_attachment_deleted', 'CREATE TABLE yumpoo.attachment_blob',
  "operation_type IN ('PUBLISH', 'CLEANUP')", 'CREATE TABLE yumpoo.attachment_maintenance_run',
  'cursor_value', 'CREATE TABLE yumpoo.attachment_reconciliation_issue', 'PUBLISHED_ORPHAN',
  'QUOTA_MISMATCH', 'STALE_SCAN_TASK']) assert(migration.includes(fragment), `V38 缺少 ${fragment}`)

for (const fragment of ['@GetMapping("/attachments/{attachmentId}/content")',
  '@DeleteMapping("/attachments/{attachmentId}")', 'AttachmentUploadPolicy.BUFFER_BYTES',
  'ContentDisposition.attachment()', 'X-Content-Type-Options', 'Content-Security-Policy']) {
  assert(controller.includes(fragment), `下载/删除 HTTP 缺少 ${fragment}`)
}
for (const forbidden of ['X-Content-SHA256', 'Accept-Ranges']) {
  assert(!controller.includes(forbidden), `下载响应泄漏或误宣告 ${forbidden}`)
}
for (const fragment of ['"DELETE","deleteAttachment"', 'ATTACHMENT_DELETED',
  'filestorage.attachment_deleted', 'parents.requireWritable', 'previousRowVersion']) {
  assert(application.includes(fragment), `删除事务缺少 ${fragment}`)
}
for (const fragment of ['storage.inspect(blob)', 'DEPENDENCY_UNAVAILABLE', 'recordReconciliationIssue',
  'claimPublish', 'releasePublish']) assert(lifecycle.includes(fragment), `生命周期缺少 ${fragment}`)
for (const fragment of ["status <> 'DELETED'", "status='DELETED'", 'available_bytes=available_bytes+:availableDelta',
  "operation_type='PUBLISH'"]) assert(repository.includes(fragment), `JDBC 删除/租约缺少 ${fragment}`)
for (const fragment of ['EXPIRE_INTENTS', 'TEMPORARY_FILES', 'VERIFY_BLOBS', 'RECONCILE_QUOTAS',
  'RECONCILE_SCANS', 'ORPHANS', 'Duration.ofHours(24)', 'getMaintenanceBatchSize',
  "status IN ('UPLOADING','AVAILABLE','DELETED')", 'claimCleanup']) {
  assert(maintenance.includes(fragment), `维护闭环缺少 ${fragment}`)
}
for (const fragment of ['LinkOption.NOFOLLOW_LINKS', 'Files.isSymbolicLink', 'deleteTemporary',
  'deletePublished', 'STORAGE_KEY']) assert(storage.includes(fragment), `存储安全缺少 ${fragment}`)
assert(properties.includes('cleanupDeleteEnabled') && properties.includes('cleanupApprovalReference'),
  '物理删除开关或批准引用缺失')

for (const fragment of ['operationId: deleteAttachment', 'downloadAttachmentContent', 'AttachmentDeleteRequest:',
  'canDownloadContent', 'canDelete', 'private, no-store', 'Content-Security-Policy']) {
  assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
}
for (const fragment of ['deleteAttachment', 'downloadAttachmentContent']) assert(sdk.includes(fragment), `SDK 缺少 ${fragment}`)
for (const forbidden of ['sha256', 'storageKey', 'scanner', 'path']) {
  assert(!eventSchema.includes(`"${forbidden}"`), `DELETED 事件泄漏 ${forbidden}`)
}
for (const fragment of ['/api/v1/attachments/${encodeURIComponent(item.id)}/content',
  'ElMessageBox.prompt', 'idempotencyKey: key', 'ifMatch: item.etag',
  'problem.status === 409 || problem.status === 412', 'await load()']) {
  assert(panel.includes(fragment), `Web 下载/删除缺少 ${fragment}`)
}
for (const fragment of ['AVAILABLE 使用同源流式下载链接', '稳定幂等键与理由']) {
  assert(panelTest.includes(fragment), `Web 验收缺少 ${fragment}`)
}
for (const fragment of ['"38"', 'v37DatabaseUpgradesForwardThroughV38AndBackfillsBlobRegistry']) {
  assert(migrationTest.includes(fragment), `V37→V38 迁移验收缺少 ${fragment}`)
}
for (const fragment of ['attachment_blob', 'attachmentBlobRegistryReconciled']) {
  assert(backup.includes(fragment), `备份恢复断言缺少 ${fragment}`)
}
assert(backupReportSchema.includes('attachmentBlobRegistryReconciled')
  && backupReportExample.includes('attachmentBlobRegistryReconciled'), 'M0-17 恢复收据契约未同步 blob 注册表断言')
for (const fragment of ['PUBLISH/CLEANUP', '连续观察', 'M5-17', 'canDownloadContent/canDelete']) {
  assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
}
assert(readme.includes('## M2-19 附件下载、逻辑删除与安全维护'), 'README 未同步 M2-19')
assert(report.milestone === 'M2-19' && report.flywayVersion === '38', '验证报告无效')
for (const requirement of ['ATTACHMENT-DOWNLOAD', 'ATTACHMENT-LOGICAL-DELETE',
  'ATTACHMENT-MAINTENANCE', 'ATTACHMENT-RECONCILIATION', 'ATTACHMENT-WEB-CLOSEOUT',
  'ATTACHMENT-MIGRATION-RESTORE']) {
  assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement), `验收矩阵缺少 ${requirement}`)
}
assert(acceptance.deferredRequirements.some(item => item.requirementId === 'ATTACHMENT-FORMAL-BLOB-RETENTION'
  && item.targetMilestone === 'M5-17'), 'M5-17 延期边界无效')
assert(acceptance.deferredRequirements.some(item => item.requirementId === 'FEEDBACK-ATTACHMENTS'
  && item.targetMilestone === 'M3B'), 'Feedback 延期边界无效')

console.log('M2-19 下载、删除、维护、对账、Web 与恢复证据资产有效。')
function assert(condition, message) {
  if (!condition) throw new Error(`M2-19 资产验证失败：${message}`)
}
