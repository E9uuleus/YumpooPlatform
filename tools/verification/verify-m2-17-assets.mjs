import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const migration = read('backend/src/main/resources/db/migration/workitem/V36__enforce_work_item_update_lifecycle.sql')
const domain = read('backend/src/main/java/com/yumpoo/platform/workitem/domain/WorkItemUpdate.java')
const service = read('backend/src/main/java/com/yumpoo/platform/workitem/application/WorkItemUpdateService.java')
const repository = read('backend/src/main/java/com/yumpoo/platform/workitem/infrastructure/JdbcWorkItemUpdateRepository.java')
const controller = read('backend/src/main/java/com/yumpoo/platform/workitem/api/WorkItemUpdateController.java')
const moderationGuard = read('backend/src/main/java/com/yumpoo/platform/catalog/api/ProjectModerationGuard.java')
const architectureRules = read('backend/src/test/java/com/yumpoo/platform/architecture/ArchitectureRules.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const sdk = read('packages/api-client/src/generated/apis/WorkItemUpdatesApi.ts')
const catalog = read('contracts/events/catalog.yaml')
const editedSchema = read('contracts/events/schemas/workitem.work-item-update-edited-v1.schema.json')
const deletedSchema = read('contracts/events/schemas/workitem.work-item-update-deleted-v1.schema.json')
const discussion = read('frontend/web-app/src/components/collaboration/WorkItemDiscussion.vue')
const discussionTest = read('frontend/web-app/src/components/collaboration/WorkItemDiscussion.spec.ts')
const domainTest = read('backend/src/test/java/com/yumpoo/platform/workitem/domain/WorkItemUpdateTest.java')
const httpTest = read('backend/src/test/java/com/yumpoo/platform/workitem/api/WorkItemHttpIT.java')
const migrationTest = read('backend/src/test/java/com/yumpoo/platform/YumpooServerApplicationIT.java')
const backup = read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const note = read('.agents/notes/implemented/architecture/2026-08-24-work-item-update-contract.md')
const readme = read('README.md')
const report = JSON.parse(read('evidence/m2-17/verification-report.json'))
const acceptance = JSON.parse(read('evidence/m2-17/acceptance-matrix.json'))

for (const fragment of ['ck_work_item_update_edit_facts', 'ck_work_item_update_delete_facts',
  'deleted_by_user_id = author_user_id', 'delete_reason = btrim(delete_reason)',
  'char_length(delete_reason) BETWEEN 1 AND 500',
  "status = 'DELETED'", 'edited_by_user_id = author_user_id']) {
  assert(migration.includes(fragment), `V36 缺少 ${fragment}`)
}
for (const fragment of ['WorkItemUpdate edit(', 'selfDelete(', 'moderateDelete(',
  '!now.isBefore(editDeadlineAt)', 'bodyHtml.equals']) {
  assert(domain.includes(fragment), `Update 聚合缺少 ${fragment}`)
}
for (const fragment of ['lockForModeration', 'lockForFactWrite', 'appendIndependent',
  'WORK_ITEM_UPDATE_MODERATION_FAILED', 'workitem.work_item_update_edited',
  'workitem.work_item_update_deleted', 'removedMentionedUserIds', 'safeSummary']) {
  assert(service.includes(fragment), `Update 服务缺少 ${fragment}`)
}
for (const fragment of ['DELETE FROM yumpoo.work_item_update_mention',
  'body_html=NULL, body_text=NULL', 'row_version=:expectedVersion']) {
  assert(repository.includes(fragment), `Update 持久化缺少 ${fragment}`)
}
for (const fragment of ['@GetMapping("/work-item-updates/{updateId}")',
  '@PatchMapping("/work-item-updates/{updateId}")',
  '@DeleteMapping("/work-item-updates/{updateId}")', 'ifMatch.parseForVisibleResource']) {
  assert(controller.includes(fragment), `Update HTTP 缺少 ${fragment}`)
}
assert(moderationGuard.includes('ProjectModerationSnapshot lockForModeration'), '缺少公开 Project 治理锁端口')
assert(architectureRules.includes('"filestorage", "audit"'), '模块矩阵未登记 workitem 到 audit.api 的依赖')
for (const fragment of ['/work-item-updates/{updateId}', 'getWorkItemUpdate', 'editWorkItemUpdate',
  'deleteWorkItemUpdate', 'canModerateDelete', 'IfMatch']) {
  assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
}
for (const fragment of ['getWorkItemUpdate', 'editWorkItemUpdate', 'deleteWorkItemUpdate']) {
  assert(sdk.includes(fragment), `生成 SDK 缺少 ${fragment}`)
}
for (const eventType of ['workitem.work_item_update_edited', 'workitem.work_item_update_deleted']) {
  assert(catalog.includes(eventType), `事件 catalog 缺少 ${eventType}`)
}
for (const schema of [editedSchema, deletedSchema]) {
  assert(!schema.includes('"bodyHtml"') && !schema.includes('"bodyText"'), '生命周期事件不得携带正文')
}
for (const fragment of ['canSelfDelete(item)', 'canModerateDelete(item)', 'saveEdit',
  'ElMessageBox.confirm', 'ElMessageBox.prompt', 'refreshUpdate', 'ErrorCode.VersionConflict',
  '治理理由：{{ item.deleteReason }}']) {
  assert(discussion.includes(fragment), `Web 讨论交互缺少 ${fragment}`)
}
for (const fragment of ['412 后单条刷新且保留未提交编辑草稿', '作者删除二次确认',
  '治理删除要求理由', '到达服务端截止时刻后主动隐藏作者操作']) {
  assert(discussionTest.includes(fragment), `Web 验收缺少 ${fragment}`)
}
for (const fragment of ['editsOnlyBeforeDeadlineAndKeepsNoopStable',
  'selfDeleteHasNoReasonAndCannotRunAtDeadline',
  'moderationCanDeleteAfterDeadlineAndNormalizesReason']) {
  assert(domainTest.includes(fragment), `领域验收缺少 ${fragment}`)
}
for (const fragment of ['workItemUpdateEditUsesStrongEtagReplacesMentionsAndKeepsParentStable',
  'workItemUpdateSelfDeleteAndArchivedOwnerModerationKeepTombstonesAndAudit',
  'workItemUpdateConcurrentMutationHasOneWinnerAndAuditOutboxFailuresRollbackEverything',
  'm217_fail_outbox', 'm217_fail_audit']) {
  assert(httpTest.includes(fragment), `HTTP/事务验收缺少 ${fragment}`)
}
for (const fragment of ['"36"', 'v35DatabaseUpgradesForwardThroughV36WithoutRewritingPublishedMigration',
  'upgraded.migrationsExecuted).isOne()']) {
  assert(migrationTest.includes(fragment), `V36 迁移验收缺少 ${fragment}`)
}
for (const fragment of ["'DELETED'", 'edited_by_user_id', 'deleted_by_user_id',
  'delete_reason', "coalesce(update_record.body_html, '<NULL>')"]) {
  assert(backup.includes(fragment), `备份恢复缺少 ${fragment}`)
}
for (const fragment of ['M2-16/M2-17', '截止时刻本身已经超窗', '失败审计',
  'M2-20 从事件投影和查询 Activity']) {
  assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
}
assert(readme.includes('## M2-17 Update 编辑、删除与治理删除'), 'README 未同步 M2-17')
assert(report.milestone === 'M2-17' && report.status === 'PASS' && report.flywayVersion === '36',
  '验证报告无效')
assert(report.testCounts.backendUnit >= 391 && report.testCounts.backendIntegration >= 205
  && report.testCounts.web >= 169, '验证报告测试计数未覆盖 M2-17 增量')
for (const requirement of ['WORK-ITEM-UPDATE-EDIT', 'WORK-ITEM-UPDATE-DELETE',
  'WORK-ITEM-UPDATE-GOVERNANCE', 'WORK-ITEM-UPDATE-WEB']) {
  assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement),
    `验收矩阵未确认 ${requirement}`)
}
assert(acceptance.deferredRequirements.some(item => item.requirementId === 'WORK-ITEM-ACTIVITY-PROJECTION'
  && item.targetMilestone === 'M2-20'), '验收矩阵未明确将 Activity 延期到 M2-20')

console.log('M2-17 生命周期、授权、事件审计、Web、迁移备份与证据资产有效。')

function assert(condition, message) {
  if (!condition) throw new Error(`M2-17 资产验证失败：${message}`)
}
