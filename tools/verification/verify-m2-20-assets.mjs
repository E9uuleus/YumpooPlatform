import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const migration = read('backend/src/main/resources/db/migration/audit/V44__create_activity_projection.sql')
const projection = read('backend/src/main/java/com/yumpoo/platform/audit/api/ActivityProjectionService.java')
const query = read('backend/src/main/java/com/yumpoo/platform/audit/application/ActivityService.java')
  + read('backend/src/main/java/com/yumpoo/platform/audit/application/ActivityCursorCodec.java')
  + read('backend/src/main/java/com/yumpoo/platform/audit/infrastructure/JdbcActivityRepository.java')
const controller = read('backend/src/main/java/com/yumpoo/platform/administration/api/ActivityController.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const sdk = read('packages/api-client/src/generated/apis/ActivityApi.ts')
const timeline = read('frontend/web-app/src/components/collaboration/ActivityTimeline.vue')
const backup = read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const postgresProjection = read('backend/src/test/java/com/yumpoo/platform/audit/infrastructure/ActivityProjectionIT.java')
const note = read('.agents/notes/implemented/architecture/2026-08-30-activity-projection-contract.md')
const readme = read('README.md')
const acceptance = JSON.parse(read('evidence/m2-20/acceptance-matrix.json'))
const report = JSON.parse(read('evidence/m2-20/verification-report.json'))

for (const fragment of ['CREATE TABLE yumpoo.activity_event', 'CREATE TABLE yumpoo.activity_projection_state',
  'accepted_from', 'UNIQUE (event_id, projection_code, scope_type, scope_id)',
  "projection_code = 'ACTIVITY_V1'", "scope_type IN ('PROJECT', 'PRODUCT', 'FEEDBACK')",
  'idx_activity_event_scope_cursor', 'idx_activity_event_primary_work_item_cursor',
  'idx_activity_event_secondary_work_item_cursor']) assert(migration.includes(fragment), `V44 缺少 ${fragment}`)
for (const fragment of ['event.occurredAt().isBefore(repository.acceptedFrom())',
  'ACTIVITY_INVALID_V1_PAYLOAD', 'mentionCount', 'assigneeUserId', 'ObjectNode safe']) {
  assert(projection.includes(fragment), `投影映射缺少 ${fragment}`)
}
for (const forbidden of ['payload.deepCopy()', 'deleteReason")', 'reasonReference']) {
  assert(!projection.includes(forbidden), `投影实现疑似保存禁用字段 ${forbidden}`)
}
for (const fragment of ['occurred_at, id', 'fingerprint', 'INVALID_CURSOR', 'MAX_SIZE = 100']) {
  assert(query.includes(fragment), `游标查询缺少 ${fragment}`)
}
for (const fragment of ['@GetMapping("/projects/{projectId}/activity")',
  '@GetMapping("/work-items/{workItemId}/activity")', 'findVisible', 'findIncludingDeleted']) {
  assert(controller.includes(fragment), `Activity HTTP 缺少 ${fragment}`)
}
for (const fragment of ['operationId: listProjectActivity', 'operationId: listWorkItemActivity',
  'historyStartedAt', 'ActivityPage:']) assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
for (const fragment of ['listProjectActivity', 'listWorkItemActivity']) assert(sdk.includes(fragment), `SDK 缺少 ${fragment}`)
for (const fragment of ['formatDateOnly', '加载更早动态', 'historyStartedAt', 'requestSequence',
  'props.workItemId', 'type="datetimerange"']) assert(timeline.includes(fragment), `Web 时间线缺少 ${fragment}`)
assert(!timeline.includes('v-html'), 'Activity 摘要不得使用 v-html')
for (const fragment of ['createActivityFact', 'readActivityFact', 'yumpoo.activity_event']) {
  assert(backup.includes(fragment), `备份恢复覆盖缺少 ${fragment}`)
}
for (const fragment of ['ACTIVITY_V1:PROJECT', 'newFixedThreadPool(2)',
  'idx_activity_event_secondary_work_item_cursor']) {
  assert(postgresProjection.includes(fragment), `PostgreSQL 投影验证缺少 ${fragment}`)
}
for (const fragment of ['## Problem', '## Decision', '## Alternatives considered', '## Consequences',
  '不回填', '跨 Project']) assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
assert(readme.includes('## M2-20 Activity 追加投影与游标查询'), 'README 未同步 M2-20')
assert(report.milestone === 'M2-20' && report.flywayVersion === '44', '验证报告无效')
for (const requirement of ['ACTIVITY-CUTOVER-PERSISTENCE', 'ACTIVITY-SAFE-PROJECTION',
  'ACTIVITY-CURSOR-ACL', 'ACTIVITY-CONTRACT-SDK', 'ACTIVITY-WEB']) {
  assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement), `验收矩阵缺少 ${requirement}`)
}

console.log('M2-20 Activity 投影、查询、契约、Web 与治理证据资产有效。')
function assert(condition, message) {
  if (!condition) throw new Error(`M2-20 资产验证失败：${message}`)
}
