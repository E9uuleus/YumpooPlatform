import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { readFreezeManifest } from '../events/event-contract-compat.mjs'
import { runPnpmSync, runSync } from './process-utils.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const manifest = readFreezeManifest(root)
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-m223-assets-'))
const baseline = path.join(temporary, 'event-contract-baseline.json')
const baseRef = process.env.YUMPOO_M223_BASE_REF ?? 'origin/dev'

try {
  runPnpmSync(['run', 'validate:event-contracts'], { cwd: root })
  runPnpmSync(['run', 'test:event-contract-compat'], { cwd: root })
  runPnpmSync(['run', 'audit:workitem-event-inventory'], { cwd: root })
  runSync(process.execPath, [path.join(root, 'tools', 'events',
    'extract-event-contract-baseline.mjs'), baseRef, baseline], { cwd: root })
  runPnpmSync(['run', 'check:event-contract-compat', '--', baseline], { cwd: root })

  const note = read('.agents/notes/implemented/data/2026-08-31-work-item-event-contract-freeze.md')
  const readme = read('README.md')
  const activity = read('backend/src/main/java/com/yumpoo/platform/audit/api/ActivityProjectionService.java')
  const outboxTest = read('backend/src/test/java/com/yumpoo/platform/foundation/consistency/M011TransactionalOutboxIT.java')
  const catalog = read('contracts/events/catalog.yaml')
  const acceptance = JSON.parse(read('evidence/m2-23/acceptance-matrix.json'))
  const report = JSON.parse(read('evidence/m2-23/verification-report.json'))

  assert(manifest.events.length === 14, '冻结清单必须包含 14 个事件')
  for (const fragment of ['## Problem', '## Decision', '## Alternatives considered',
    '## Consequences', '同一 v1 只允许增加可选字段', '当前端点范围']) {
    assert(note.includes(fragment), `Agent Note 缺少 ${fragment}`)
  }
  assert(readme.includes('## M2-23 Work Item 领域事件契约冻结'), 'README 未同步 M2-23')
  for (const stale of ['M2-23 事件冻结和 M2-24', 'M2-23 最终事件冻结和 M2-24']) {
    assert(!readme.includes(stale), `README 仍保留旧延期描述 ${stale}`)
  }
  for (const eventType of ['workitem.content_created', 'workitem.content_updated',
    'workitem.content_archived', 'workitem.content_restored',
    'filestorage.attachment_available', 'filestorage.attachment_deleted']) {
    assert(catalog.includes(`eventType: ${eventType}`), `既有契约目录缺少 ${eventType}`)
  }
  for (const fragment of ['CONTENT_EVENTS', 'ATTACHMENT_EVENTS', 'appendContent', 'appendAttachment']) {
    assert(activity.includes(fragment), `Activity 既有回归缺少 ${fragment}`)
  }
  for (const fragment of ['missingConsumerAndUnsupportedVersionBecomeObservablePermanentFailures',
    'UNSUPPORTED_EVENT_VERSION']) {
    assert(outboxTest.includes(fragment), `未知版本永久失败回归缺少 ${fragment}`)
  }
  assert(report.milestone === 'M2-23' && report.flywayVersion === '44', '验证报告无效')
  for (const requirement of ['WORK-ITEM-EVENT-INVENTORY', 'WORK-ITEM-EVENT-COMPATIBILITY',
    'WORK-ITEM-EVENT-RUNTIME-CONFORMANCE', 'WORK-ITEM-EVENT-RECIPIENT-SAFETY',
    'CONTENT-ATTACHMENT-EVENT-REGRESSION']) {
    assert(acceptance.verifiedSlices.some(item => item.requirementId === requirement),
      `验收矩阵缺少 ${requirement}`)
  }
  console.log('M2-23 冻结清单、兼容基线、生产者/消费者审计、回归与证据资产有效。')
} finally {
  fs.rmSync(temporary, { recursive: true, force: true })
}

function assert(condition, message) {
  if (!condition) throw new Error(`M2-23 资产验证失败：${message}`)
}
