import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const migration = read('backend/src/main/resources/db/migration/administration/V28__create_admin_override.sql')
const collector = read('backend/src/main/java/com/yumpoo/platform/administration/application/ProjectArchiveBlockerCollector.java')
const governance = read('backend/src/main/java/com/yumpoo/platform/administration/application/GovernanceOverrideService.java')
const lifecycle = read('backend/src/main/java/com/yumpoo/platform/administration/application/ProjectLifecycleGovernanceService.java')
const lifecycleTest = read('backend/src/test/java/com/yumpoo/platform/administration/application/ProjectLifecycleGovernanceServiceTest.java')
const controller = read('backend/src/main/java/com/yumpoo/platform/administration/api/ProjectLifecycleGovernanceController.java')
const page = read('frontend/web-app/src/components/projects/ProjectLifecycleActions.vue')
const pageTest = read('frontend/web-app/src/components/projects/ProjectLifecycleActions.spec.ts')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const events = read('contracts/events/catalog.yaml')
const note = read('.agents/notes/implemented/product/2026-08-21-project-lifecycle-governance-contract.md')
const archiveNote = read('.agents/notes/implemented/product/2026-10-09-project-archive-and-deletion.md')

for (const fragment of ['CREATE TABLE yumpoo.admin_override', 'request_hash', 'before_snapshot', 'blocker_counts']) {
  assert(migration.includes(fragment), `V28 缺少 ${fragment}`)
}
for (const fragment of ['coverage mismatch', 'DEPENDENCY_UNAVAILABLE', '!report.complete()']) {
  assert(collector.includes(fragment), `blocker 关闭失败协议缺少 ${fragment}`)
}
assert(!collector.includes('Noop') && !collector.includes('EmptyProvider'), '禁止空 blocker provider')
for (const fragment of ['stableFailure', 'PROJECT_ARCHIVE_WITH_OPEN_ITEMS']) assert(governance.includes(fragment), `治理覆盖缺少 ${fragment}`)
for (const fragment of ['catalog.project_archived', 'catalog.project_reopened']) assert(lifecycle.includes(fragment), `生命周期缺少 ${fragment}`)
const ordinaryArchive = lifecycle.slice(lifecycle.indexOf('public IdempotencyExecutionResult archive('),
  lifecycle.indexOf('public IdempotencyExecutionResult restore('))
assert(ordinaryArchive.includes('PlatformRoleCode.COMPANY_ADMIN') && ordinaryArchive.includes('projects.archive(mutation)'),
  '普通归档缺少负责人/管理员操作')
assert(!ordinaryArchive.includes('blockers'), '普通归档不应调用兼容治理入口的 blocker 收集器')
for (const fragment of ['normalArchiveDoesNotConsultBlockersForOwnerOrAdministrator', 'verifyNoInteractions(blockers)',
  'ownerAndAdministratorCanRestore', 'ordinaryMemberCannotRestoreAndNoMutationOrAuditIsWritten']) {
  assert(lifecycleTest.includes(fragment), `归档恢复权限验收缺少 ${fragment}`)
}
for (const fragment of ['workspace-moves', 'legacyMove', 'INVALID_STATE_TRANSITION']) {
  assert(controller.includes(fragment), `HTTP 缺少 Project Workspace deprecated 兼容适配 ${fragment}`)
}
for (const fragment of ['/projects/{projectId}/workspace-moves:', 'deprecated: true']) {
  assert(openapi.includes(fragment), `OpenAPI 缺少 Project Workspace deprecated 兼容面 ${fragment}`)
}
for (const fragment of ['/projects/{projectId}/archive:', '/projects/{projectId}/restore:', '/admin/governance-overrides:']) {
  assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
}
for (const fragment of ['projectsApi.archiveProject', 'projectsApi.restoreProject', 'ifMatch: props.project.etag',
  'idempotencyKey: crypto.randomUUID()', '仍可归档', '只读浏览和恢复', '项目不再计入统计', '项目已被其他操作更新']) {
  assert(page.includes(fragment), `项目端生命周期闭环缺少 ${fragment}`)
}
assert(!page.includes('治理覆盖归档') && !page.includes('canOverrideArchive') && !page.includes('problem.error.details.blockers'),
  '项目端不应重新显示治理覆盖或开放事项 blocker 入口')
for (const fragment of ['归档项目支持负责人恢复入口', '开放事项不会阻止归档', '提示数量查询失败时仍允许确认归档']) {
  assert(pageTest.includes(fragment), `普通归档提示与恢复验收缺少 ${fragment}`)
}
assert(!page.includes('迁移 Workspace'), '项目端仍显示 Workspace 迁移')
assert(events.includes('catalog.project_moved_to_workspace'), '历史迁移事件 schema 必须继续可读')
assert(note.includes('Status: implemented') && note.includes('不再支持跨 Workspace 迁移'), '生命周期 Note 未同步 MAIN 事实')
assert(note.includes('2026-10-09-project-archive-and-deletion.md'), '原生命周期 Note 未关联归档权限的部分替代决定')
for (const fragment of ['Status: implemented', '负责人或 CompanyAdmin', '未关闭工作项不再阻止归档',
  '前端移除覆盖入口', '普通归档不调用它']) {
  assert(archiveNote.includes(fragment), `归档决策未同步 ${fragment}`)
}

console.log('M2-08 负责人/管理员普通归档与恢复、非阻塞数量提示和只读浏览有效；治理覆盖 API 及其 blocker 关闭失败协议、Workspace v1 拒绝适配继续兼容。')
function assert(condition, message) { if (!condition) throw new Error(`M2-08 资产验证失败：${message}`) }
