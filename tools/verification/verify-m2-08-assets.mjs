import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const migration = read('backend/src/main/resources/db/migration/administration/V28__create_admin_override.sql')
const collector = read('backend/src/main/java/com/yumpoo/platform/administration/application/ProjectArchiveBlockerCollector.java')
const governance = read('backend/src/main/java/com/yumpoo/platform/administration/application/GovernanceOverrideService.java')
const lifecycle = read('backend/src/main/java/com/yumpoo/platform/administration/application/ProjectLifecycleGovernanceService.java')
const controller = read('backend/src/main/java/com/yumpoo/platform/administration/api/ProjectLifecycleGovernanceController.java')
const page = read('frontend/web-app/src/components/projects/ProjectLifecycleActions.vue')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const events = read('contracts/events/catalog.yaml')
const note = read('.agents/notes/implemented/product/2026-08-21-project-lifecycle-governance-contract.md')

for (const fragment of ['CREATE TABLE yumpoo.admin_override', 'request_hash', 'before_snapshot', 'blocker_counts']) {
  assert(migration.includes(fragment), `V28 缺少 ${fragment}`)
}
for (const fragment of ['coverage mismatch', 'DEPENDENCY_UNAVAILABLE', '!report.complete()']) {
  assert(collector.includes(fragment), `blocker 关闭失败协议缺少 ${fragment}`)
}
assert(!collector.includes('Noop') && !collector.includes('EmptyProvider'), '禁止空 blocker provider')
for (const fragment of ['stableFailure', 'PROJECT_ARCHIVE_WITH_OPEN_ITEMS']) assert(governance.includes(fragment), `治理覆盖缺少 ${fragment}`)
for (const fragment of ['catalog.project_archived', 'catalog.project_reopened']) assert(lifecycle.includes(fragment), `生命周期缺少 ${fragment}`)
assert(!controller.includes('workspace-moves'), 'HTTP 仍暴露 Project Workspace 迁移')
assert(!openapi.includes('/projects/{projectId}/workspace-moves:'), 'OpenAPI 仍暴露 Project Workspace 迁移')
for (const fragment of ['/projects/{projectId}/archive:', '/projects/{projectId}/restore:', '/admin/governance-overrides:']) {
  assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
}
for (const fragment of ['治理覆盖归档', 'problem.error.details.blockers', 'Project 已被其他操作更新']) {
  assert(page.includes(fragment), `项目端生命周期闭环缺少 ${fragment}`)
}
assert(!page.includes('迁移 Workspace'), '项目端仍显示 Workspace 迁移')
assert(events.includes('catalog.project_moved_to_workspace'), '历史迁移事件 schema 必须继续可读')
assert(note.includes('Status: implemented') && note.includes('不再支持跨 Workspace 迁移'), '生命周期 Note 未同步 MAIN 事实')

console.log('M2-08 归档、恢复、覆盖与 blocker 关闭失败协议有效；Workspace 迁移已安全退役。')
function assert(condition, message) { if (!condition) throw new Error(`M2-08 资产验证失败：${message}`) }
