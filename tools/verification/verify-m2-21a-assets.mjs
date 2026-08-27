import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const migration = read('backend/src/main/resources/db/migration/workitem/V43__create_work_item_relation.sql')
const service = read('backend/src/main/java/com/yumpoo/platform/workitem/application/WorkItemService.java')
const repository = read('backend/src/main/java/com/yumpoo/platform/workitem/infrastructure/JdbcWorkItemRepository.java')
const relationRepository = read('backend/src/main/java/com/yumpoo/platform/workitem/infrastructure/JdbcWorkItemRelationRepository.java')
const controller = read('backend/src/main/java/com/yumpoo/platform/workitem/api/WorkItemController.java')
const integration = read('backend/src/test/java/com/yumpoo/platform/workitem/api/WorkItemHttpIT.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const sdk = read('packages/api-client/src/generated/apis/WorkItemsApi.ts')
const eventSchema = read('contracts/events/schemas/workitem.work-item-relation-created-v1.schema.json')
const overview = read('frontend/web-app/src/views/projects/ProjectOverviewView.vue')
const subtable = read('frontend/web-app/src/components/projects/ProjectWorkItemSubitemsTable.vue')
const subtableTest = read('frontend/web-app/src/components/projects/ProjectWorkItemSubitemsTable.spec.ts')
const note = read('.agents/notes/implemented/data/2026-08-28-work-item-parent-child-relations.md')
const readme = read('README.md')
const acceptance = JSON.parse(read('evidence/m2-21a/acceptance-matrix.json'))
const report = JSON.parse(read('evidence/m2-21a/verification-report.json'))

for (const fragment of ['CREATE TABLE yumpoo.work_item_relation', "'PARENT_CHILD'", "'RELATED'",
  "'BLOCKS'", "'SOURCE'", "'DUPLICATE'", 'uq_work_item_relation_active_parent',
  'left_project_id = right_project_id', 'row_version']) assert(migration.includes(fragment), `V43 缺少 ${fragment}`)
for (const fragment of ['createSubitem', 'listSubitems', 'subitemOrderMove',
  'workitem.work_item_relation_created', 'NESTED_SUBITEM_NOT_SUPPORTED']) {
  assert(service.includes(fragment), `WorkItemService 缺少 ${fragment}`)
}
for (const fragment of ['findSubitems', 'work_item_relation relation', 'rootsOnly']) {
  assert(repository.includes(fragment), `根/子项查询缺少 ${fragment}`)
}
assert(relationRepository.includes('countActiveChildren'), '关系仓储缺少批量直接子项计数')
for (const fragment of ['/subitems")', '/subitems/{subitemId}/order-moves']) {
  assert(controller.includes(fragment), `Controller 缺少 ${fragment}`)
}
for (const fragment of ['subitemsAreNestedIdempotentRootFilteredAndSiblingScoped',
  'INVALID_STATE_TRANSITION', 'workitem.work_item_relation_created']) {
  assert(integration.includes(fragment), `后端集成验收缺少 ${fragment}`)
}
for (const fragment of ['operationId: listWorkItemSubitems', 'operationId: createWorkItemSubitem',
  'operationId: moveWorkItemSubitemOrder', 'subitemCount:', 'WorkItemSubitemList:',
  'WorkItemSubitemCreateRequest:']) assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
for (const fragment of ['listWorkItemSubitems', 'createWorkItemSubitem',
  'moveWorkItemSubitemOrder']) assert(sdk.includes(fragment), `生成 SDK 缺少 ${fragment}`)
for (const fragment of ['workitem.work_item_relation_created', 'relationType',
  'leftWorkItemId', 'rightWorkItemId']) assert(eventSchema.includes(fragment), `关系事件缺少 ${fragment}`)
for (const fragment of ['expandedSubitemIds', 'loadSubitems', 'onTableExpandChange',
  'project-work-item-subitems-table']) assert(overview.includes(fragment), `项目表格缺少 ${fragment}`)
for (const fragment of ['aria-label', 'margin-left: 32px', 'height: 36px',
  'createWorkItemSubitem', 'moveWorkItemSubitemOrder']) assert(subtable.includes(fragment), `子表格缺少 ${fragment}`)
for (const fragment of ['默认继承父项 Content', '直接兄弟锚点', '不渲染下一级展开入口']) {
  assert(subtableTest.includes(fragment), `子表格验收缺少 ${fragment}`)
}
for (const fragment of ['根查询语义', 'M2-21', '不级联', '递归环检测']) {
  assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
}
assert(readme.includes('## M2-21A 项目工作项直接子项'), 'README 未同步 M2-21A')
assert(report.milestone === 'M2-21A' && report.flywayVersion === '43', '验证报告无效')
for (const requirement of ['WORK-ITEM-PARENT-CHILD-DATA', 'WORK-ITEM-SUBITEM-API',
  'WORK-ITEM-ROOT-QUERY', 'WORK-ITEM-SUBITEM-TABLE', 'WORK-ITEM-RELATION-EVENT']) {
  assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement), `验收矩阵缺少 ${requirement}`)
}
assert(acceptance.deferredRequirements.some(item => item.requirementId === 'WORK-ITEM-GENERAL-RELATIONS'
  && item.targetMilestone === 'M2-21'), 'M2-21 剩余范围未诚实延期')

console.log('M2-21A 父子数据、根查询、接口、事件、单层表格与证据资产有效。')
function assert(condition, message) {
  if (!condition) throw new Error(`M2-21A 资产验证失败：${message}`)
}
