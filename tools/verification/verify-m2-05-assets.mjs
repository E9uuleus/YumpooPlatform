import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..','..')
const read=(relative)=>fs.readFileSync(path.join(root,relative),'utf8')
const v24=read('backend/src/main/resources/db/migration/catalog/V24__allow_owner_membership_removal_without_reason.sql')
const v25=read('backend/src/main/resources/db/migration/administration/V25__extend_owner_missing_governance_to_project.sql')
const service=read('backend/src/main/java/com/yumpoo/platform/administration/application/ProjectMembershipGovernanceService.java')
const repository=read('backend/src/main/java/com/yumpoo/platform/catalog/infrastructure/project/JdbcProjectMembershipRepository.java')
const projection=read('backend/src/main/java/com/yumpoo/platform/administration/infrastructure/governance/JdbcProjectOwnerGovernanceProjection.java')
const test=read('backend/src/test/java/com/yumpoo/platform/administration/application/ProjectMembershipGovernanceIT.java')
const backup=read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const openapi=read('contracts/openapi/yumpoo-v1.yaml')
const events=read('contracts/events/catalog.yaml')
const projectsApi=read('packages/api-client/src/generated/apis/ProjectsApi.ts')
const note=read('.agents/notes/implemented/product/2026-08-20-project-membership-governance.md')
const report=JSON.parse(read('evidence/m2-05/verification-report.json'))
const acceptance=JSON.parse(read('evidence/m2-05/acceptance-matrix.json'))

for(const fragment of ['remove_reason IS NULL','removed_at IS NOT NULL']) assert(v24.includes(fragment),`V24 缺少 ${fragment}`)
for(const fragment of ["target_type IN ('PRODUCT', 'PROJECT')"]) assert(v25.includes(fragment),`V25 缺少 ${fragment}`)
for(const fragment of ['addProjectMember','removeProjectMember','reassignProjectOwner','appendIndependent',
  'catalog.project_member_added','catalog.project_member_removed','catalog.project_owner_reassigned'])
  assert(service.includes(fragment),`治理服务缺少 ${fragment}`)
assert(repository.includes('LEFT JOIN yumpoo.project_membership') && repository.includes(':admin OR m.id IS NOT NULL'),
  'Project 可见性没有在 SQL 中约束')
assert(repository.includes('findByUsers'), '候选 membership 没有批量补充')
for(const fragment of ['identity.user_employment_left','identity.user_account_disabled',
  "target_type='PROJECT'",'PROJECT_OWNER_MISSING']) assert(projection.includes(fragment),`治理投影缺少 ${fragment}`)
for(const fragment of ['ownerCanListSearchAddRemoveAndReactivateWithoutReason',
  'adminRequiresReasonAndReassignmentKeepsOldOwnerActive','concurrentOwnerReassignmentsHaveSingleWinner',
  'auditFailureRollsBackMembershipEventAndIdempotency','ownerAvailabilityEventsOpenAndResolveProjectIssueIdempotently',
  'permissionMatrixHidesOrDeniesAtTheCorrectBoundary'])
  assert(test.includes(fragment),`PostgreSQL 验收缺少 ${fragment}`)
for(const fragment of ['membership_facts',"'OWNER_MISSING','PROJECT'",'issue_version'])
  assert(backup.includes(fragment),`备份恢复缺少 ${fragment}`)
for(const fragment of ['/projects/{projectId}/members:','/projects/{projectId}/member-candidates:',
  '/projects/{projectId}/owner-reassignments:','ProjectMember:','ProjectOwnerReassignmentRequest:'])
  assert(openapi.includes(fragment),`OpenAPI 缺少 ${fragment}`)
for(const fragment of ['catalog.project_member_added','catalog.project_member_removed','catalog.project_owner_reassigned'])
  assert(events.includes(fragment),`事件目录缺少 ${fragment}`)
for(const fragment of ['listProjectMembers','listProjectMemberCandidates','reassignProjectOwner'])
  assert(projectsApi.includes(fragment),`生成客户端缺少 ${fragment}`)
assert(note.includes('Status: implemented') && note.includes('M2-05 不实现 Project 列表'), 'Agent Note 状态或边界不完整')
assert(report.milestone==='M2-05' && report.status==='PASS' && report.flywayVersion==='25','验证报告无效')
for(const id of ['ACL-002','ACL-004','ACL-006','ACL-011','ACL-012','ACL-013'])
  assert(acceptance.verifiedSlices.some(item=>item.requirementId===id),`验收矩阵缺少 ${id}`)

console.log('M2-05 成员事实、唯一负责人、契约、治理投影、测试、备份恢复和范围边界资产有效。')
function assert(condition,message){if(!condition)throw new Error(`M2-05 资产验证失败：${message}`)}
