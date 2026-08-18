import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8')

const migration = read('backend/src/main/resources/db/migration/templateworkflow/V16__create_project_template_catalog.sql')
const controller = read('backend/src/main/java/com/yumpoo/platform/administration/api/ProjectTemplateController.java')
const governance = read('backend/src/main/java/com/yumpoo/platform/administration/application/ProjectTemplateGovernanceService.java')
const publishedPort = read('backend/src/main/java/com/yumpoo/platform/templateworkflow/api/PublishedProjectTemplateQuery.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const events = read('contracts/events/catalog.yaml')
const evidence = JSON.parse(read('evidence/m2-01/verification-report.json'))
const acceptance = JSON.parse(read('evidence/m2-01/acceptance-matrix.json'))

for (const fragment of [
  'CREATE TABLE yumpoo.project_template_definition',
  'CREATE TABLE yumpoo.project_template_content_blueprint',
  'CREATE TABLE yumpoo.workflow_status_definition',
  'CREATE TABLE yumpoo.workflow_transition_definition',
  "('20000000-0000-4000-8000-000000000001', 'RND', 1, 'RND_V1'",
  "('20000000-0000-4000-8000-000000000002', 'PRE_SALES', 1, 'PRE_SALES_V1'",
  "('20000000-0000-4000-8000-000000000003', 'IMPLEMENTATION', 1, 'IMPLEMENTATION_V1'",
  "('20000000-0000-4000-8000-000000000004', 'HYPERCARE', 1, 'HYPERCARE_V1'",
  'guard_project_template_structure_mutation',
  'guard_project_template_definition_mutation',
]) assert(migration.includes(fragment), `V16 迁移缺少：${fragment}`)

for (const fragment of [
  '/project-templates',
  '/admin/project-templates/{templateKey}/versions/{version}',
  'IfMatchParser',
  'IdempotencyKeyParser',
]) assert(controller.includes(fragment), `模板 Controller 缺少：${fragment}`)

for (const fragment of [
  'IdempotentCommandExecutor',
  'TransactionalEventPort',
  'SecurityAuditAppendPort',
  'templateworkflow.project_template_published',
  'templateworkflow.project_template_retired',
]) assert(governance.includes(fragment), `模板治理编排缺少：${fragment}`)

assert(publishedPort.includes('List<ProjectTemplateSnapshot> findAllPublished()'), '公开端口未提供可选版本列表')

for (const fragment of [
  '/project-templates:',
  '/admin/project-templates/{templateKey}/versions/{version}:',
  '/publish:',
  '/retire:',
  'ProjectTemplateVersion:',
]) assert(openapi.includes(fragment), `OpenAPI 缺少：${fragment}`)

for (const fragment of [
  'templateworkflow.project_template_published',
  'templateworkflow.project_template_retired',
]) assert(events.includes(fragment), `事件目录缺少：${fragment}`)

assert(evidence.milestone === 'M2-01' && evidence.status === 'PASS', '验收报告未记录 M2-01 通过')
assert(evidence.flywayVersion === '16', '验收报告 Flyway 版本不是 V16')
assert(
  acceptance.verifiedRequirements.some(({ requirementId }) => requirementId === 'PPM-005'),
  '追踪证据未验证 PPM-005',
)
assert(
  acceptance.deferredRequirements.some(
    ({ requirementId, targetMilestone }) => requirementId === 'PPM-006' && targetMilestone === 'M2-04',
  ),
  '追踪证据未保留 PPM-006 的 M2-04 边界',
)

console.log('M2-01 迁移、公开端口、治理事务、OpenAPI、事件与范围边界资产有效。')

function assert(condition, message) {
  if (!condition) throw new Error(`M2-01 资产验证失败：${message}`)
}
