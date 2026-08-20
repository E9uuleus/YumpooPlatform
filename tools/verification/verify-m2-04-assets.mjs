import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8')

const projectMigration = read('backend/src/main/resources/db/migration/catalog/V21__create_project_catalog.sql')
const contentMigration = read('backend/src/main/resources/db/migration/workitem/V22__create_content_catalog.sql')
const idempotencyMigration = read('backend/src/main/resources/db/migration/foundation/V23__preserve_idempotent_response_text.sql')
const orchestrator = read('backend/src/main/java/com/yumpoo/platform/administration/application/ProjectCreationOrchestrator.java')
const templateRepository = read('backend/src/main/java/com/yumpoo/platform/templateworkflow/infrastructure/JdbcProjectTemplateRepository.java')
const postgresTest = read('backend/src/test/java/com/yumpoo/platform/administration/application/ProjectCreationIT.java')
const httpTest = read('backend/src/test/java/com/yumpoo/platform/administration/api/ProjectCreationHttpIT.java')
const backupRestore = read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const events = read('contracts/events/catalog.yaml')
const projectsApi = read('packages/api-client/src/generated/apis/ProjectsApi.ts')
const note = read('.agents/notes/implemented/product/2026-08-20-project-creation-contract.md')
const evidence = JSON.parse(read('evidence/m2-04/verification-report.json'))
const acceptance = JSON.parse(read('evidence/m2-04/acceptance-matrix.json'))

for (const fragment of [
  'CREATE TABLE yumpoo.project', 'CREATE TABLE yumpoo.project_membership',
  'uq_project_company_code', 'fk_project_workspace_company', 'fk_project_owner_company',
  'DEFERRABLE INITIALLY DEFERRED', 'project owner must have an active membership',
]) assert(projectMigration.includes(fragment), `V21 迁移缺少：${fragment}`)

for (const fragment of [
  'CREATE TABLE yumpoo.content', 'uq_content_project_code',
  'fk_content_project_template_scope', 'applied_blueprint_code', 'view_config',
]) assert(contentMigration.includes(fragment), `V22 迁移缺少：${fragment}`)

for (const fragment of ['response_text text', 'response_json::text', 'byte-for-byte']) {
  assert(idempotencyMigration.includes(fragment), `V23 迁移缺少：${fragment}`)
}

for (const fragment of [
  'requireCompanyAdmin', 'findPublishedForCreation', 'projectCommandPort.create',
  'initializeContentsPort.initialize', 'appendAudit', 'appendCreated',
  'appendTemplateApplied', 'catalog.project_created', 'catalog.project_template_applied',
]) assert(orchestrator.includes(fragment), `创建编排缺少：${fragment}`)

assert(templateRepository.includes('FOR SHARE'), '模板创建查询未持有共享锁')
assert(orchestrator.indexOf('projectCommandPort.create') < orchestrator.indexOf('initializeContentsPort.initialize'),
  '创建编排顺序不是 Project 后 Content')
assert(orchestrator.indexOf('initializeContentsPort.initialize') < orchestrator.indexOf('appendAudit'),
  '创建编排顺序不是 Content 后 Audit')

for (const fragment of [
  'createsFourTypesWithOwnerMembershipAndTemplateProvenance',
  'replayIsIdenticalAndConcurrentDuplicateCodeHasOneWinner',
  'secondContentAuditAndBothOutboxFailuresRollBackEveryFact',
  'deferredConstraintRejectsOwnerWithoutActiveMembership',
  'publishedTemplateShareLockSerializesRetirement',
]) assert(postgresTest.includes(fragment), `PostgreSQL 验收缺少：${fragment}`)

for (const fragment of [
  'authenticationCsrfAndCompanyAdminBoundaryProtectCreation',
  'fourTypesCreateDraftAndReplayExactlyTheSameResponse',
  'invalidOwnerWorkspaceTemplateMismatchDuplicateAndKeyReuseAreStable',
]) assert(httpTest.includes(fragment), `HTTP 验收缺少：${fragment}`)

assert(backupRestore.includes('createProjectContentFact(source)'), '备份恢复未注入 Project/Content 事实')
assert(backupRestore.includes('readProjectContentFact(target)'), '备份恢复未验证 Project/Content 事实')

for (const fragment of ['/projects:', 'ProjectCreateRequest:', 'Project:', 'ProjectLifecycle:']) {
  assert(openapi.includes(fragment), `OpenAPI 缺少：${fragment}`)
}
for (const fragment of ['catalog.project_created', 'catalog.project_template_applied']) {
  assert(events.includes(fragment), `事件目录缺少：${fragment}`)
}
assert(projectsApi.includes('export class ProjectsApi'), '生成客户端缺少 ProjectsApi')
assert(note.includes('Status: implemented'), 'Project Agent Note 未进入 implemented 生命周期')
assert(note.includes('M2-04 不实现列表、详情、PATCH、激活、成员管理'), 'Agent Note 未记录里程碑边界')
assert(evidence.milestone === 'M2-04' && evidence.status === 'PASS', '验收报告未记录 M2-04 通过')
assert(evidence.flywayVersion === '23', '验收报告 Flyway 版本不是 V23')

for (const requirementId of ['PPM-004', 'PPM-005', 'PPM-006', 'PPM-008', 'ACL-002']) {
  assert(acceptance.verifiedSlices.some((slice) => slice.requirementId === requirementId),
    `追踪证据未验证 ${requirementId}`)
}
for (const [requirementId, targetMilestone] of [
  ['PPM-007', 'M2-06'], ['PROJECT-MEMBERSHIP', 'M2-05'],
  ['CONTENT-API', 'M2-09'], ['ACTIVITY-PROJECT', 'M2-20'],
]) {
  assert(acceptance.deferredRequirements.some((item) =>
    item.requirementId === requirementId && item.targetMilestone === targetMilestone),
  `追踪证据未保留 ${requirementId} 的 ${targetMilestone} 边界`)
}

console.log('M2-04 Project 原子创建、模板固化、初始 Content、契约、测试、文档与范围边界资产有效。')

function assert(condition, message) {
  if (!condition) throw new Error(`M2-04 资产验证失败：${message}`)
}
