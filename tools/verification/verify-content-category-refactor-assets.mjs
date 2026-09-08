import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { verifyContentCategoryRefactorAssets } from './content-category-refactor-assets.mjs'
import { readBreakingChangeExceptions } from '../openapi/breaking-change-policy.mjs'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

verifyContentCategoryRefactorAssets()

const exceptions = readBreakingChangeExceptions(root)
const exception = exceptions.find(entry => entry.id === '2026-09-02-content-category-refactor')
assert(exception, '缺少类别重构的历史精确例外')
assert(/^[a-f0-9]{64}$/u.test(exception.oldSha256) && /^[a-f0-9]{64}$/u.test(exception.newSha256), 'OpenAPI 历史哈希无效')
assert(fs.existsSync(path.join(root, exception.agentNote)), '破坏变更清单关联的 Agent Note 不存在')

console.log('Content 类别契约与历史精确例外有效；当前规范兼容性由 PR 基线检查负责。')

function assert(condition, message) {
  if (!condition) throw new Error(`Content 类别重构专项验证失败：${message}`)
}
