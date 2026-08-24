import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const baseMigration = read('backend/src/main/resources/db/migration/catalog/V17__create_workspace_catalog.sql')
const singletonMigration = read('backend/src/main/resources/db/migration/catalog/V32__consolidate_main_workspace.sql')
const controller = read('backend/src/main/java/com/yumpoo/platform/catalog/api/WorkspaceController.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const events = read('contracts/events/catalog.yaml')
const note = read('.agents/notes/implemented/product/2026-08-23-main-workspace-contract.md')

for (const fragment of ['CREATE TABLE yumpoo.workspace', 'uq_workspace_company_code', 'ck_workspace_code']) {
  assert(baseMigration.includes(fragment), `V17 历史基线缺少 ${fragment}`)
}
for (const fragment of [
  'main_workspace_selection', "w.code = 'MAIN'", "w.status = 'ACTIVE'", 'UPDATE yumpoo.project',
  'DELETE FROM yumpoo.workspace', 'uq_workspace_company_singleton', 'ck_workspace_main_code',
  'ck_workspace_main_sort_order', 'ck_workspace_main_status', 'provision_main_workspace',
  'require_company_main_workspace', 'DEFERRABLE INITIALLY DEFERRED',
]) assert(singletonMigration.includes(fragment), `V32 MAIN 迁移缺少 ${fragment}`)

for (const fragment of ['@GetMapping("/workspaces")', '@GetMapping("/workspaces/{workspaceId}")', '@PatchMapping("/workspaces/{workspaceId}")']) {
  assert(controller.includes(fragment), `Workspace 只读/改名接口缺少 ${fragment}`)
}
for (const forbidden of ['@PostMapping("/workspaces")', '/archive")', '/restore")']) {
  assert(!controller.includes(forbidden), `Workspace Controller 仍暴露 ${forbidden}`)
}
assert(!openapi.includes('/workspaces/{workspaceId}/archive:'), 'OpenAPI 仍暴露 Workspace 归档')
assert(!openapi.includes('/workspaces/{workspaceId}/restore:'), 'OpenAPI 仍暴露 Workspace 恢复')
for (const historical of ['catalog.workspace_created', 'catalog.workspace_archived', 'catalog.workspace_restored']) {
  assert(events.includes(historical), `历史事件目录缺少 ${historical}`)
}
assert(note.includes('Status: implemented') && note.includes('code=MAIN'), 'MAIN Agent Note 未实施或缺少固定身份')
assert(note.includes('备份') && note.includes('不可逆'), 'MAIN Agent Note 未记录迁移回滚义务')

console.log('M2-02 当前资产已收口为每 Company 唯一 MAIN，并保留历史事件兼容。')
function assert(condition, message) { if (!condition) throw new Error(`M2-02 资产验证失败：${message}`) }
