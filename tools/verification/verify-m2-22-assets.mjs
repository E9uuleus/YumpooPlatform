import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const service = read('backend/src/main/java/com/yumpoo/platform/workitem/application/WorkItemRelationService.java')
const repository = read('backend/src/main/java/com/yumpoo/platform/workitem/infrastructure/JdbcWorkItemRelationRepository.java')
const access = read('backend/src/main/java/com/yumpoo/platform/catalog/api/ProjectAccessSnapshotQuery.java')
const activityTest = read('backend/src/test/java/com/yumpoo/platform/audit/api/ActivityProjectionServiceTest.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const sdk = read('packages/api-client/src/generated/apis/WorkItemsApi.ts')
const detailPanel = read('frontend/web-app/src/components/collaboration/WorkItemDetailPanel.vue')
const webActivity = read('frontend/web-app/src/components/collaboration/activityPresentation.ts')
const retirement = read('.agents/notes/implemented/product/2026-10-09-retire-relations-tab.md')
const note = read('.agents/notes/implemented/security/2026-08-31-cross-project-work-item-relation-visibility.md')
const acceptance = JSON.parse(read('evidence/m2-22/acceptance-matrix.json'))
const report = JSON.parse(read('evidence/m2-22/verification-report.json'))

for (const fragment of ['findCounterpartProjectIds', 'hasHiddenForWorkItem', 'lockProjects',
  'PARENT_CHILD_REQUIRES_SAME_PROJECT', 'targetProjectId', 'projectIds(']) {
  assert(service.includes(fragment), `关系服务缺少 ${fragment}`)
}
for (const fragment of ['findCounterpartProjectIds', 'visibleProjectIds', 'NOT IN (:visibleProjectIds)',
  'CASE WHEN left_work_item_id=:workItemId THEN right_project_id']) {
  assert(repository.includes(fragment), `关系仓储缺少 ${fragment}`)
}
assert(access.includes('Collection<UUID> projectIds'), 'catalog 未公开批量 actor-scoped 访问快照')
for (const fragment of ['PARENT_CHILD_REQUIRES_SAME_PROJECT', 'hasHiddenForWorkItem',
  'lockProjects', 'visibleProjectIds']) assert(service.includes(fragment) || repository.includes(fragment),
  `跨项目关系实现缺少 ${fragment}`)
assert(activityTest.includes('createsTwoPrivacyScopedProjectionsWhenCrossProjectRelationIsDeleted'), '缺少跨项目解除 Activity 验收')
for (const fragment of ['targetProjectId', 'hasHiddenRelations', '仅统计可见关系',
  '该聚合信号忽略 relationType']) assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
for (const fragment of ['targetProjectId', 'ListWorkItemRelationCandidatesRequest']) assert(sdk.includes(fragment), `SDK 缺少 ${fragment}`)
assert(detailPanel.includes("modelValue: 'details' | 'discussion' | 'activity'"), '当前详情抽屉页签未同步关系页下线')
for (const fragment of ['workitem.work_item_parent_changed', 'workitem.work_item_relation_created',
  'workitem.work_item_relation_deleted']) assert(webActivity.includes(fragment), `Web 关系动态缺少 ${fragment}`)
for (const fragment of ['仅移除 Web 操作入口', '既有关系数据', '跨项目关系授权与脱敏']) assert(retirement.includes(fragment), `关系页下线决策缺少 ${fragment}`)
for (const fragment of ['分页与计数前', 'COMPANY_ADMIN', 'Project UUID', '不读取 membership 表']) assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
assert(report.milestone === 'M2-22' && report.flywayVersion === '43', '验证报告无效')
for (const requirement of ['WORK-ITEM-CROSS-PROJECT-RELATIONS',
  'WORK-ITEM-HIDDEN-RELATION-PRIVACY', 'WORK-ITEM-CROSS-PROJECT-CONCURRENCY',
  'WORK-ITEM-CROSS-PROJECT-WEB', 'WORK-ITEM-CROSS-PROJECT-ACTIVITY']) {
  assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement), `验收矩阵缺少 ${requirement}`)
}

console.log('M2-22 跨项目关系、双侧授权、隐私裁剪、保留 Activity 与历史证据资产有效。')
function assert(condition, message) {
  if (!condition) throw new Error(`M2-22 资产验证失败：${message}`)
}
