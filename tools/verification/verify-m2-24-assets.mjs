import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { runPnpmSync, runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m224-assets-'))
const baseline = path.join(temporary, 'event-contract-baseline.json')
const baseRef = process.env.YUMPOO_M224_BASE_REF ?? 'origin/dev'

try {
  runPnpmSync(['run', 'validate:event-contracts'], { cwd: root })
  runPnpmSync(['run', 'test:event-contract-compat'], { cwd: root })
  runSync(process.execPath, [path.join(root, 'tools', 'events',
    'extract-event-contract-baseline.mjs'), baseRef, baseline], { cwd: root })
  runPnpmSync(['run', 'check:event-contract-compat', '--', baseline], { cwd: root })

  const exitNote = read('.agents/notes/implemented/architecture/2026-08-31-m2-exit-and-m3-public-ports.md')
  const productNote = read('.agents/notes/implemented/product/2026-08-20-product-lifecycle-contract.md')
  const projectNote = read('.agents/notes/implemented/product/2026-08-21-project-lifecycle-governance-contract.md')
  const readme = read('README.md')
  const migration = read('backend/src/main/resources/db/migration/administration/V45__add_product_governance_override.sql')
  const schema = JSON.parse(read('contracts/events/schemas/catalog.product-archived-v1.schema.json'))
  const payloadRequired = schema.allOf[1].properties.payload.required
  const acceptance = JSON.parse(read('evidence/m2-24/acceptance-matrix.json'))
  const report = JSON.parse(read('evidence/m2-24/verification-report.json'))

  for (const fragment of ['## Problem', '## Decision', '## Alternatives considered',
    '## Consequences', 'Project → 按 UUID 排序的 Product', 'WorkItemReferenceQuery']) {
    assert(exitNote.includes(fragment), `M2/M3 边界 Agent Note 缺少 ${fragment}`)
  }
  assert(productNote.includes('ACTIVE_DEVELOPMENT_SUPPORT_PROJECTS'), 'Product 生命周期 Note 未同步真实 blocker')
  assert(projectNote.includes('M3A-13') && projectNote.includes('M3B-11'), 'Project 生命周期 Note 未同步延期门禁')
  assert(readme.includes('## M2-24 项目协作阶段收口'), 'README 未同步 M2-24')
  assert(migration.includes('PRODUCT_ARCHIVE_WITH_BLOCKERS') && migration.includes("'PRODUCT'"),
    'V45 未扩展 Product 治理约束')
  assert(!payloadRequired.includes('mode') && !payloadRequired.includes('blockers'),
    'Product 归档 v1 新字段必须保持可选')
  assert(report.milestone === 'M2-24' && report.flywayVersion === '45', '验证报告无效')
  for (const requirement of ['PRODUCT-ARCHIVE-BLOCKERS', 'PRODUCT-GOVERNANCE-WEB',
    'M3-PUBLIC-PORTS', 'M2-CROSS-SLICE-REGRESSION']) {
    assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement),
      `验收矩阵缺少 ${requirement}`)
  }
  for (const requirement of ['M3A-WORKLOG-BLOCKER', 'M3B-FEEDBACK-BLOCKERS',
    'PROJECT-BLOCKER-TOTAL-GATE', 'MAIN-WORKSPACE-UI', 'PRODUCT-OWNER-REASSIGNMENT-UI',
    'SCHEDULED-REMINDERS']) {
    assert(acceptance.deferredRequirements.some(item => item.requirementId === requirement),
      `验收矩阵缺少延期项 ${requirement}`)
  }
  console.log('M2-24 治理契约、公开端口、延期边界与阶段证据资产有效。')
} finally {
  fs.rmSync(temporary, { recursive: true, force: true })
}

function assert(condition, message) {
  if (!condition) throw new Error(`M2-24 资产验证失败：${message}`)
}
