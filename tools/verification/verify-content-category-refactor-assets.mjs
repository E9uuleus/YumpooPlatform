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
assert(manifest.schemaVersion === 1 && manifest.exceptions.length === 1, '破坏变更清单必须只有本次精确例外')
const exception = manifest.exceptions[0]
assert(exception.id === '2026-09-02-content-category-refactor', '破坏变更清单 ID 无效')
assert(exception.newSha256 === digest('contracts/openapi/yumpoo-v1.yaml'), 'OpenAPI 当前哈希与清单不匹配')
assert(/^[a-f0-9]{64}$/u.test(exception.oldSha256), 'OpenAPI 历史哈希无效')
assert(fs.existsSync(path.join(root, exception.agentNote)), '破坏变更清单关联的 Agent Note 不存在')

const note = read(exception.agentNote)
for (const fragment of ['Status: implemented', '精确 SHA-256', '严格兼容检查', '合入 `dev`']) {
  assert(note.includes(fragment), `OpenAPI 破坏变更 Agent Note 缺少 ${fragment}`)
}

console.log('Content 类别重构、OpenAPI 精确哈希例外与知识资产有效。')

function assert(condition, message) {
  if (!condition) throw new Error(`Content 类别重构专项验证失败：${message}`)
}
