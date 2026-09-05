import { createHash } from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verifyContentCategoryRefactorAssets } from './content-category-refactor-assets.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const digest = relative => createHash('sha256').update(fs.readFileSync(path.join(root, relative))).digest('hex')

verifyContentCategoryRefactorAssets()

const manifest = JSON.parse(read('tools/openapi/breaking-change-exceptions.json'))
assert(manifest.schemaVersion === 1 && Array.isArray(manifest.exceptions), '破坏变更清单结构无效')
assert(new Set(manifest.exceptions.map(entry => entry.id)).size === manifest.exceptions.length, '破坏变更清单 ID 重复')
const exception = manifest.exceptions.find(entry => entry.id === '2026-09-02-content-category-refactor')
assert(exception, '缺少类别重构的历史精确例外')
assert(/^[a-f0-9]{64}$/u.test(exception.oldSha256) && /^[a-f0-9]{64}$/u.test(exception.newSha256), 'OpenAPI 历史哈希无效')
const current = manifest.exceptions.find(entry => entry.oldSha256 === exception.oldSha256
  && entry.newSha256 === digest('contracts/openapi/yumpoo-v1.yaml'))
assert(current, 'OpenAPI 当前规范缺少同一历史基线的精确例外')
assert(typeof current.reason === 'string' && current.reason.trim(), '当前精确例外缺少变更原因')
assert(typeof current.agentNote === 'string' && fs.existsSync(path.join(root, current.agentNote)), '当前精确例外缺少决策 Note')
assert(read(current.agentNote).includes('Status: implemented'), '当前精确例外未关联活动实施决定')
assert(fs.existsSync(path.join(root, exception.agentNote)), '破坏变更清单关联的 Agent Note 不存在')

const note = read(exception.agentNote)
for (const fragment of ['Status: implemented', '精确 SHA-256', '严格兼容检查', '合入 `dev`']) {
  assert(note.includes(fragment), `OpenAPI 破坏变更 Agent Note 缺少 ${fragment}`)
}

console.log('Content 类别重构、OpenAPI 精确哈希例外与知识资产有效。')

function assert(condition, message) {
  if (!condition) throw new Error(`Content 类别重构专项验证失败：${message}`)
}
