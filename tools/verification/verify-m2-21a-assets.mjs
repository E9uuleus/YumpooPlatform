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
for (const fragment of ['switchingCategoryPreservesIdentityHierarchyDiscussionAndProjectRanks',
  '/subitems', 'work_item_relation', 'projectSortBefore']) {
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
  'project-work-item-subitems-table', '--work-item-hierarchy-gap: 14px',
  '--work-item-table-scroll-left', 'syncSubitemFixedColumnScrollPosition',
  'subitemMovableColumnOrder', 'visibleSubitemColumns', 'moveSubitemColumn',
  'monday-add-column-icon', 'M10 2.25C10.4142 2.25',
  'monday-quick-add__field', 'placeholder="添加工作项"', 'contentId: defaultContentId.value',
  'monday-quick-checkbox', 'translateX(2px)',
  '--work-item-quick-control-height: 26px', '.monday-quick-row:focus-within',
  'background: var(--yp-bg-selected)', 'outline: none !important',
  'height: var(--work-item-table-row-height)']) {
  assert(overview.includes(fragment), `项目表格缺少 ${fragment}`)
}
for (const fragment of ['aria-label', '--work-item-hierarchy-indent, 40px',
  '--work-item-hierarchy-line-width, 1px', '--work-item-hierarchy-bar-width, 6px',
  '--subitem-hierarchy-corner-radius', 'border-bottom-left-radius: var(--subitem-hierarchy-corner-radius)',
  '.subitem-table-frame::after', 'background-position: var(--subitem-hierarchy-bar-width) top',
  'background: var(--yp-monday-grid-border, var(--yp-border-subtle))',
  'left: var(--subitem-hierarchy-bar-width)', 'bottom: var(--subitem-hierarchy-line-width)',
  'monday-subitem-table--empty', 'subitem-hierarchy-bar__trailing',
  '--subitem-add-row-accent: rgba(87, 155, 252, 0.5)',
  'subitem-hierarchy-branch--add::before', 'border-color: var(--subitem-add-row-accent)',
  '--subitem-table-row-height: 36px', 'subitem-hierarchy-branch--data', 'subitem-block-column',
  'monday-sortable-column-header', 'monday-column-resize-handle', 'onColumnPointerDown',
  '--subitem-sort-overflow-space: 20px', '.el-table__header-wrapper',
  'th.monday-sortable-column-header:has(.sort-by-column--active)',
  'createColumnDragPreview', 'columnDragStyle', 'subitem-column-drag-source', 'columnDropAllowed',
  '.el-table__body-wrapper tbody tr.el-table__row',
  'SUBITEM_ADD_COLUMN_MIN_WIDTH', 'subitem-add-column-header', 'subitem-add-column-button',
  'M10 2.25C10.4142 2.25',
  '--subitem-table-quick-height: var(--subitem-table-row-height)', 'subitem-add__field',
  'placeholder="添加子项"', 'contentId: defaultContentId.value',
  'subitem-quick-checkbox', 'color-mix(in srgb, var(--yp-border-strong) 50%, transparent)',
  '--subitem-quick-control-height: 26px', '.subitem-quick-row:focus-within',
  '.monday-subitem-table.el-table--border::after',
  'translateX(var(--work-item-table-scroll-left, 0px))',
  'createWorkItemSubitem', 'moveWorkItemSubitemOrder']) assert(subtable.includes(fragment), `子表格缺少 ${fragment}`)
assert(!subtable.includes('subitem-hierarchy-branch--header'), '子表头不应渲染层级连接锚点')
for (const fragment of ['自动继承父项类别而非首个启用类别', '直接兄弟锚点', '不渲染下一级展开入口',
  '表头不渲染连接锚点', '空子项时让添加行紧接表头', '状态和优先级标签占满对应子项单元格',
  '固定勾选和名称列，并让单行表头整列可拖出表格但仅在当前子表内落下']) {
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
