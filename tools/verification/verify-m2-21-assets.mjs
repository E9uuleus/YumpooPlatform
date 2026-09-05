import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const service = read('backend/src/main/java/com/yumpoo/platform/workitem/application/WorkItemRelationService.java')
const repository = read('backend/src/main/java/com/yumpoo/platform/workitem/infrastructure/JdbcWorkItemRelationRepository.java')
const controller = read('backend/src/main/java/com/yumpoo/platform/workitem/api/WorkItemRelationController.java')
const integration = read('backend/src/test/java/com/yumpoo/platform/workitem/api/WorkItemHttpIT.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const sdk = read('packages/api-client/src/generated/apis/WorkItemsApi.ts')
const activity = read('backend/src/main/java/com/yumpoo/platform/audit/api/ActivityProjectionService.java')
const web = read('frontend/web-app/src/components/collaboration/WorkItemRelations.vue')
const webTest = read('frontend/web-app/src/components/collaboration/WorkItemRelations.spec.ts')
const note = read('.agents/notes/implemented/data/2026-08-28-work-item-parent-child-relations.md')
const readme = read('README.md')
const acceptance = JSON.parse(read('evidence/m2-21/acceptance-matrix.json'))
const report = JSON.parse(read('evidence/m2-21/verification-report.json'))

for (const fragment of ['WorkItemRelationType', 'findActivePair', 'validateParentChild',
  'workitem.work_item_relation_deleted', 'workitem.work_item_parent_changed',
  'CROSS_PROJECT_RELATION_NOT_SUPPORTED', 'lockRelationEndpoints']) {
  assert(service.includes(fragment), `关系服务缺少 ${fragment}`)
}
for (const fragment of ['CandidateFacts', 'already_related', 'parent_is_child',
  'child_has_children', 'candidate_content.name AS content_name',
  'candidate_content.color_token AS content_color_token',
  'parent_content.name AS parent_content_name',
  'ORDER BY relation.created_at DESC, relation.id ASC']) {
  assert(repository.includes(fragment), `关系仓储缺少 ${fragment}`)
}
for (const fragment of ['/relations")', '/relation-candidates")', '/parent-changes")',
  '@DeleteMapping("/work-item-relations/{relationId}")']) assert(controller.includes(fragment), `HTTP 缺少 ${fragment}`)
for (const fragment of ['operationId: listWorkItemRelations', 'operationId: listWorkItemRelationCandidates',
  'operationId: createWorkItemRelation', 'operationId: changeWorkItemParent',
  'operationId: deleteWorkItemRelation', 'counterpartVisible']) assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
for (const fragment of ['listWorkItemRelations', 'listWorkItemRelationCandidates',
  'createWorkItemRelation', 'changeWorkItemParent', 'deleteWorkItemRelation']) assert(sdk.includes(fragment), `SDK 缺少 ${fragment}`)
for (const fragment of ['switchingCategoryPreservesIdentityHierarchyDiscussionAndProjectRanks',
  'right_work_item_id', 'projectSortBefore', '.isOne()']) assert(integration.includes(fragment), `HTTP 验收缺少 ${fragment}`)
for (const fragment of ['workitem.work_item_relation_deleted', 'workitem.work_item_parent_changed']) assert(activity.includes(fragment), `Activity 缺少 ${fragment}`)
for (const fragment of ['WorkItemRelationCandidateEligibilityEnum.ReparentRequired', 'ElMessageBox.confirm', 'deleteWorkItemRelation',
  'mutationKey', '关系事实已刷新', 'counterpart.deleted']) assert(web.includes(fragment), `Web 缺少 ${fragment}`)
for (const fragment of ['已删除对端', '原子接口', '提交原因与 ETag']) assert(webTest.includes(fragment), `Web 验收缺少 ${fragment}`)
for (const fragment of ['永久限定为两层', '不实现递归 DAG', 'M2-22', '原子换父']) assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
assert(readme.includes('## M2-21 同项目 Work Item 普通关系'), 'README 未同步 M2-21')
assert(report.milestone === 'M2-21' && report.flywayVersion === '43', '验证报告无效')
for (const requirement of ['WORK-ITEM-GENERAL-RELATIONS', 'WORK-ITEM-PARENT-CHANGE',
  'WORK-ITEM-RELATION-WEB', 'WORK-ITEM-RELATION-EVENTS']) {
  assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement), `验收矩阵缺少 ${requirement}`)
}
assert(acceptance.deferredRequirements.some(item => item.requirementId === 'WORK-ITEM-CROSS-PROJECT-RELATIONS'
  && item.targetMilestone === 'M2-22'), 'M2-22 边界未诚实延期')

console.log('M2-21 同项目普通关系、两层父子、事件、Web 与证据资产有效。')
function assert(condition, message) {
  if (!condition) throw new Error(`M2-21 资产验证失败：${message}`)
}
