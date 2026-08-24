import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','..')
const read=(relative)=>fs.readFileSync(path.join(root,relative),'utf8')
const migration=read('backend/src/main/resources/db/migration/V26__add_project_visibility_query_indexes.sql')
const repository=read('backend/src/main/java/com/yumpoo/platform/catalog/infrastructure/project/JdbcProjectRepository.java')
const service=read('backend/src/main/java/com/yumpoo/platform/catalog/application/project/ProjectService.java')
const activation=read('backend/src/main/java/com/yumpoo/platform/administration/application/ProjectActivationOrchestrator.java')
const tests=read('backend/src/test/java/com/yumpoo/platform/administration/application/ProjectCreationIT.java')
const backup=read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const openapi=read('contracts/openapi/yumpoo-v1.yaml')
const events=read('contracts/events/catalog.yaml')
const sdk=read('packages/api-client/src/generated/apis/ProjectsApi.ts')
const workbench=read('frontend/web-app/src/views/projects/ProjectsView.vue')
const settings=read('frontend/web-app/src/views/projects/ProjectSettingsView.vue')
const note=read('.agents/notes/implemented/product/2026-08-20-project-query-and-activation-contract.md')
const report=JSON.parse(read('evidence/m2-06/verification-report.json'))
const acceptance=JSON.parse(read('evidence/m2-06/acceptance-matrix.json'))

for(const fragment of ['idx_project_company_lifecycle_name_code','WHERE status = \'ACTIVE\'']) assert(migration.includes(fragment),`V26 缺少 ${fragment}`)
for(const fragment of ['LEFT JOIN yumpoo.project_membership','ORDER BY p.name, p.project_code, p.id','countVisibleCurrentByWorkspace']) assert(repository.includes(fragment),`Project SQL 缺少 ${fragment}`)
for(const fragment of ['catalog.project_updated','changedFields','hasSameDetails']) assert(service.includes(fragment),`Project 服务缺少 ${fragment}`)
for(const fragment of ['OWNER_MISSING','TEMPLATE_UNAVAILABLE','ACTIVE_CONTENT_MISSING','catalog.project_activated','RETIRED']) assert(activation.includes(fragment),`激活编排缺少 ${fragment}`)
for(const fragment of ['activatesAllFourTypesAndRequiresCustomerOnlyOutsideProductDevelopment','retiredFrozenTemplateRemainsActivatable','queryAndWorkspaceCountsUseTheSameDatabaseVisibilityPredicate','projectManagementQueryCombinesSearchFiltersDatesOwnersAndAccessWithoutCountDrift']) assert(tests.includes(fragment),`PostgreSQL 验收缺少 ${fragment}`)
for(const fragment of ["'ACTIVE'",'activated_at','row_version']) assert(backup.includes(fragment),`备份恢复缺少 ${fragment}`)
for(const fragment of ['/projects/{projectId}:','/projects/{projectId}/activate:','ProjectSummary:','ProjectDetail:','ProjectUpdateRequest:','ProjectCapabilities:']) assert(openapi.includes(fragment),`OpenAPI 缺少 ${fragment}`)
for(const fragment of ['catalog.project_updated','catalog.project_activated']) assert(events.includes(fragment),`事件目录缺少 ${fragment}`)
for(const fragment of ['listProjects','getProject','updateProject','activateProject']) assert(sdk.includes(fragment),`生成 SDK 缺少 ${fragment}`)
for(const fragment of ['创建项目','搜索项目名称或编码','ProjectLifecycleFilter.All','listProjects','listProjectOwnerOptions']) assert(workbench.includes(fragment),`项目管理页缺少 ${fragment}`)
assert(settings.includes('load(false)') && settings.includes('projectUpdateRequest'), '设置页未保留 412 草稿或未发送完整快照')
assert(note.includes('Status: implemented') && note.includes('Retired 模板阻断激活'), 'Agent Note 状态或退役模板决策缺失')
assert(report.milestone==='M2-06' && report.status==='PASS' && report.flywayVersion==='26','验证报告无效')
for(const id of ['PPM-002','PPM-004','PPM-007','PPM-011','PPM-012']) assert(acceptance.verifiedSlices.some(item=>item.requirementId===id),`验收矩阵缺少 ${id}`)

console.log('M2-06 查询、激活、工作台、契约、测试、备份恢复和范围边界资产有效。')
function assert(condition,message){if(!condition)throw new Error(`M2-06 资产验证失败：${message}`)}
