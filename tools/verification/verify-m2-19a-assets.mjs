import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const migration = read('backend/src/main/resources/db/migration/workitem/V41__create_project_work_item_label_catalog.sql')
const service = read('backend/src/main/java/com/yumpoo/platform/workitem/application/WorkItemLabelService.java')
const serviceTest = read('backend/src/test/java/com/yumpoo/platform/workitem/application/WorkItemLabelServiceTest.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const sdk = read('packages/api-client/src/generated/apis/WorkItemsApi.ts')
const overview = read('frontend/web-app/src/views/projects/ProjectOverviewView.vue')
const editor = read('frontend/web-app/src/components/projects/WorkItemLabelPopoverContent.vue')
const labelColors = read('frontend/web-app/src/components/projects/workItemLabelColors.ts')
const content = read('frontend/web-app/src/views/projects/ContentWorkItemsView.vue')
const note = read('.agents/notes/implemented/data/2026-08-26-project-work-item-label-catalog.md')
const readme = read('README.md')
const acceptance = JSON.parse(read('evidence/m2-19a/acceptance-matrix.json'))
const report = JSON.parse(read('evidence/m2-19a/verification-report.json'))

for (const fragment of ['project_work_item_label_catalog', 'project_work_item_status_label',
  'project_work_item_priority_label', "'NOT_STARTED'", 'fk_work_item_project_status_label',
  'fk_work_item_project_priority_label']) assert(migration.includes(fragment), `V41 缺少 ${fragment}`)
for (const fragment of ['PROTECTED_LABEL', 'LABEL_IN_USE', '你不能删除正在使用的标签',
  'incrementVersion', 'COMPANY_ADMIN_READ_ONLY']) assert(service.includes(fragment), `标签服务缺少 ${fragment}`)
for (const fragment of ['refusesToDeactivateOrDeleteProtectedNotStartedLabel',
  'refusesToDeleteLabelReferencedByExistingWorkItem', 'keepsCompanyAdministratorReadOnly']) {
  assert(serviceTest.includes(fragment), `标签服务测试缺少 ${fragment}`)
}
for (const fragment of ['/projects/{projectId}/work-item-labels:', 'WorkItemLabelCatalog:',
  'WorkItemLabelColorToken:', 'WorkItemPriorityLabel:', 'WorkItemStatusLabel:']) {
  assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
}
for (const fragment of ['getProjectWorkItemLabels', 'createProjectWorkItemStatusLabel',
  'deleteProjectWorkItemPriorityLabel']) assert(sdk.includes(fragment), `SDK 缺少 ${fragment}`)
for (const fragment of ['workItemId: item.id', 'route.query.workItemId',
  'value-format="YYYY-MM-DD"', '@update:model-value="onDueDateChange']) {
  assert(overview.includes(fragment), `项目表格缺少 ${fragment}`)
}
for (const fragment of ['+ 新增标签', '停用标签', 'label.inUse',
  'deletedCodes.value', 'sortOrder: index + 1']) assert(editor.includes(fragment), `标签编辑器缺少 ${fragment}`)
for (const fragment of ['mondayWorkItemLabelColors', 'var(--yp-label-bright-green)',
  'var(--yp-label-pecan)']) assert(labelColors.includes(fragment), `标签色板缺少 ${fragment}`)
assert(content.includes("openLabelEditor('status')") && content.includes("openLabelEditor('priority')"),
  'Content 工作台未开放双标签编辑入口')
for (const fragment of ['部分替代', 'NOT_STARTED', '所有启用状态之间可直接迁移',
  '路由和日期修复不新增数据库事实']) assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
assert(readme.includes('## M2-19A 项目表格路由、截止日期与标签目录'), 'README 未同步 M2-19A')
assert(report.milestone === 'M2-19A' && report.flywayVersion === '41' && report.status === 'PASS',
  '验证报告无效')
for (const requirement of ['TABLE-DRAWER-ROUTE', 'DUE-DATE-IMMEDIATE-PATCH',
  'PROJECT-WORK-ITEM-LABEL-CATALOG', 'LABEL-CATALOG-THREE-VIEW-CONSISTENCY',
  'LABEL-CATALOG-CONTRACT-COMPATIBILITY']) {
  assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement), `验收矩阵缺少 ${requirement}`)
}

console.log('M2-19A 路由、日期、项目标签目录、三视图与契约证据资产有效。')
function assert(condition, message) {
  if (!condition) throw new Error(`M2-19A 资产验证失败：${message}`)
}
