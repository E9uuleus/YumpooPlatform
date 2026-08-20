import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8')

const productMigration = read('backend/src/main/resources/db/migration/catalog/V18__create_product_catalog.sql')
const governanceMigration = read('backend/src/main/resources/db/migration/administration/V19__extend_governance_issue_for_product_owner.sql')
const auditMigration = read('backend/src/main/resources/db/migration/audit/V20__widen_security_audit_reason.sql')
const productService = read('backend/src/main/java/com/yumpoo/platform/catalog/application/product/ProductService.java')
const governanceService = read('backend/src/main/java/com/yumpoo/platform/administration/application/ProductGovernanceService.java')
const projection = read('backend/src/main/java/com/yumpoo/platform/administration/infrastructure/governance/JdbcProductOwnerGovernanceProjection.java')
const catalogTest = read('backend/src/test/java/com/yumpoo/platform/catalog/infrastructure/product/ProductCatalogIT.java')
const httpTest = read('backend/src/test/java/com/yumpoo/platform/catalog/api/ProductHttpIT.java')
const backupRestore = read('backend/src/test/java/com/yumpoo/platform/filestorage/consistency/M017BackupRestoreIT.java')
const openapi = read('contracts/openapi/yumpoo-v1.yaml')
const events = read('contracts/events/catalog.yaml')
const productsApi = read('packages/api-client/src/generated/apis/ProductsApi.ts')
const note = read('.agents/notes/implemented/product/2026-08-20-product-lifecycle-contract.md')
const evidence = JSON.parse(read('evidence/m2-03/verification-report.json'))
const acceptance = JSON.parse(read('evidence/m2-03/acceptance-matrix.json'))

for (const fragment of [
  'CREATE TABLE yumpoo.product', 'uq_product_company_code', 'fk_product_owner_company',
  'ck_product_archive_facts', 'idx_product_company_status_navigation',
  'idx_product_company_owner_status_navigation',
]) assert(productMigration.includes(fragment), `V18 迁移缺少：${fragment}`)

for (const fragment of ['OWNER_MISSING', 'target_type', 'uq_governance_issue_detected_target']) {
  assert(governanceMigration.includes(fragment), `V19 迁移缺少：${fragment}`)
}
assert(auditMigration.includes('varchar(500)'), 'V20 未同步负责人重指派理由上限')

for (const fragment of [
  'findVisible', 'IdempotentCommandExecutor', 'catalog.product_created',
  'catalog.product_updated', 'hasSameDetails',
]) assert(productService.includes(fragment), `Product 应用服务缺少：${fragment}`)

for (const fragment of [
  'reassignProductOwner', 'SecurityAuditAppendPort', 'catalog.product_archived',
  'catalog.product_restored', 'catalog.product_owner_reassigned', 'OWNER_MISSING',
]) assert(governanceService.includes(fragment), `Product 治理服务缺少：${fragment}`)

for (const fragment of [
  'identity.user_employment_left', 'identity.user_account_disabled',
  'identity.user_employment_returned', 'identity.user_account_enabled',
  'OWNER_MISSING', 'PRODUCT',
]) assert(projection.includes(fragment), `OWNER_MISSING 投影缺少：${fragment}`)

for (const fragment of [
  'visibilityIsAppliedBeforePagingAndDetailsUseHiddenNotFound',
  'reassignmentAuditOutboxProductAndIdempotencyCommitOrRollbackTogether',
  'ownerMissingProjectionFansOutAndResolvesOnlyFromCurrentFacts',
  "role_code = 'PRODUCT_OWNER'",
]) assert(catalogTest.includes(fragment), `Product PostgreSQL 测试缺少：${fragment}`)

for (const fragment of [
  'productHttpLifecycleEnforcesVisibilityPreconditionsAndIdempotency',
  'invalidOwnerReturnsStableFieldViolation',
]) assert(httpTest.includes(fragment), `Product HTTP 测试缺少：${fragment}`)

assert(backupRestore.includes('createProductGovernanceFact(source)'), '备份恢复未注入 Product 治理事实')
assert(backupRestore.includes('readProductGovernanceFact(target)'), '备份恢复未验证 Product 治理事实')

for (const fragment of [
  '/products:', '/products/{productId}:', '/products/{productId}/archive:',
  '/products/{productId}/restore:', '/products/{productId}/owner-reassignments:',
  'ProductPage:', 'ProductOwnerReassignmentRequest:',
]) assert(openapi.includes(fragment), `OpenAPI 缺少：${fragment}`)

for (const fragment of [
  'catalog.product_created', 'catalog.product_updated', 'catalog.product_archived',
  'catalog.product_restored', 'catalog.product_owner_reassigned',
]) assert(events.includes(fragment), `事件目录缺少：${fragment}`)

assert(productsApi.includes('export class ProductsApi'), '生成客户端缺少 ProductsApi')
assert(note.includes('Status: implemented'), 'Product Agent Note 未进入 implemented 生命周期')
assert(note.includes('不创建 ProductProjectLink'), 'Product Agent Note 未记录归档阶段边界')
assert(evidence.milestone === 'M2-03' && evidence.status === 'PASS', '验收报告未记录 M2-03 通过')
assert(evidence.flywayVersion === '20', '验收报告 Flyway 版本不是 V20')

for (const requirementId of ['PPM-003', 'ACL-002', 'ACL-004', 'ACL-012']) {
  assert(acceptance.verifiedSlices.some((slice) => slice.requirementId === requirementId),
    `追踪证据未验证 ${requirementId}`)
}
for (const [requirementId, targetMilestone] of [
  ['PPM-004', 'M2-07'], ['PPM-002', 'M2-24'], ['PPM-015', 'M2-24'], ['PPM-015', 'M3B'],
]) {
  assert(acceptance.deferredRequirements.some((item) =>
    item.requirementId === requirementId && item.targetMilestone === targetMilestone),
  `追踪证据未保留 ${requirementId} 的 ${targetMilestone} 边界`)
}

console.log('M2-03 Product 生命周期、负责人治理、契约、测试、文档与范围边界资产有效。')

function assert(condition, message) {
  if (!condition) throw new Error(`M2-03 资产验证失败：${message}`)
}
