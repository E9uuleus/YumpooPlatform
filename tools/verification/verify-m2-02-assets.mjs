import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8')

const migration = read('backend/src/main/resources/db/migration/catalog/V17__create_workspace_catalog.sql')
const controller = read('backend/src/main/java/com/yumpoo/platform/catalog/api/WorkspaceController.java')
const service = read('backend/src/main/java/com/yumpoo/platform/catalog/application/workspace/WorkspaceService.java')
const snapshotPort = read('backend/src/main/java/com/yumpoo/platform/catalog/api/WorkspaceSnapshotQuery.java')
const integrationTest = read('backend/src/test/java/com/yumpoo/platform/catalog/infrastructure/workspace/WorkspaceCatalogIT.java')
const httpTest = read('backend/src/test/java/com/yumpoo/platform/catalog/api/WorkspaceHttpIT.java')
const backupRestore = read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const events = read('contracts/events/catalog.yaml')
const note = read('.agents/notes/implemented/product/2026-08-20-workspace-lifecycle-contract.md')
const evidence = JSON.parse(read('evidence/m2-02/verification-report.json'))
const acceptance = JSON.parse(read('evidence/m2-02/acceptance-matrix.json'))

for (const fragment of [
  'CREATE TABLE yumpoo.workspace',
  'uq_workspace_company_code',
  'fk_workspace_created_by_company',
  'fk_workspace_updated_by_company',
  'ck_workspace_code',
  'ck_workspace_status',
  'ck_workspace_row_version',
  'idx_workspace_company_status_navigation',
]) assert(migration.includes(fragment), `V17 迁移缺少：${fragment}`)

for (const fragment of [
  '@GetMapping("/workspaces")',
  '@GetMapping("/workspaces/{workspaceId}")',
  '@PostMapping("/workspaces")',
  '@PatchMapping("/workspaces/{workspaceId}")',
  '/archive',
  '/restore',
  'IfMatchParser',
  'IdempotencyKeyParser',
]) assert(controller.includes(fragment), `Workspace Controller 缺少：${fragment}`)

for (const fragment of [
  'IdempotentCommandExecutor',
  'TransactionalEventPort',
  'catalog.workspace_created',
  'catalog.workspace_updated',
  'catalog.workspace_archived',
  'catalog.workspace_restored',
  'hasSameDetails',
]) assert(service.includes(fragment), `Workspace 应用服务缺少：${fragment}`)

assert(snapshotPort.includes('Optional<WorkspaceSnapshot> findActive'), '公开端口未提供 ACTIVE 最小快照查询')

for (const fragment of [
  'eventAppendFailureRollsBackWorkspaceAndIdempotencyRecord',
  'conditionalPatchLifecycleAndSnapshotRespectVersionAndCompany',
  'createReplaysExactlyAndListUsesStableSortOrder',
]) assert(integrationTest.includes(fragment), `Workspace 集成测试缺少：${fragment}`)

for (const fragment of [
  'authenticationCsrfAndRoleBoundaryProtectWorkspaceMutations',
  'administratorLifecycleUsesStrongEtagIdempotencyAndHiddenArchivedReads',
  'duplicateCodeReturnsStableFieldValidation',
]) assert(httpTest.includes(fragment), `Workspace HTTP 测试缺少：${fragment}`)

assert(backupRestore.includes('createWorkspaceFact(source)'), '备份恢复测试未注入 Workspace 合成事实')
assert(backupRestore.includes('readWorkspaceFact(target)'), '备份恢复测试未验证 Workspace 恢复事实')

for (const fragment of [
  '/workspaces:',
  '/workspaces/{workspaceId}:',
  '/workspaces/{workspaceId}/archive:',
  '/workspaces/{workspaceId}/restore:',
  'WorkspaceList:',
  'visibleProjectCount:',
]) assert(openapi.includes(fragment), `OpenAPI 缺少：${fragment}`)

for (const fragment of [
  'catalog.workspace_created',
  'catalog.workspace_updated',
  'catalog.workspace_archived',
  'catalog.workspace_restored',
]) assert(events.includes(fragment), `事件目录缺少：${fragment}`)

assert(note.includes('Status: implemented'), 'Workspace Agent Note 未进入 implemented 生命周期')
assert(note.includes('visibleProjectCount'), 'Workspace Agent Note 未记录阶段性计数决策')

assert(evidence.milestone === 'M2-02' && evidence.status === 'PASS', '验收报告未记录 M2-02 通过')
assert(evidence.flywayVersion === '17', '验收报告 Flyway 版本不是 V17')
assert(
  acceptance.verifiedSlices.some(({ requirementId }) => requirementId === 'PPM-001'),
  '追踪证据未验证 PPM-001 的 Workspace 多实例切片',
)
for (const expected of [
  ['PPM-001', 'M2-04'],
  ['PPM-002', 'M2-24'],
  ['PPM-016', 'M2-08'],
  ['PPM-AT-001', 'M2-24'],
]) {
  assert(
    acceptance.deferredRequirements.some(
      ({ requirementId, targetMilestone }) => requirementId === expected[0] && targetMilestone === expected[1],
    ),
    `追踪证据未保留 ${expected[0]} 的 ${expected[1]} 边界`,
  )
}

console.log('M2-02 迁移、生命周期 API、公开快照端口、事件、测试、文档与范围边界资产有效。')

function assert(condition, message) {
  if (!condition) throw new Error(`M2-02 资产验证失败：${message}`)
}
