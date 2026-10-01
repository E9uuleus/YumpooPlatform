import { readFileSync } from 'node:fs'
import assert from 'node:assert/strict'
import test from 'node:test'
import { DEFAULT_PROJECT_STRUCTURE } from '../../frontend/web-app/src/components/projects/defaultProjectStructure.ts'

test('前端预览逐项匹配后端唯一默认结构，防止新增项目与预览漂移', () => {
  const java = readFileSync(new URL('../../backend/src/main/java/com/yumpoo/platform/workitem/application/DefaultProjectStructure.java', import.meta.url), 'utf8')
  const entries = (type) => [...java.matchAll(new RegExp(`new ${type}\\(([^)]+)\\)`, 'g'))].map(match => JSON.parse(`[${match[1]}]`))
  assert.deepEqual(DEFAULT_PROJECT_STRUCTURE.categories.map(({ code, name, color, sortOrder }) => [code, name, color, sortOrder]), entries('Category'))
  assert(DEFAULT_PROJECT_STRUCTURE.categories.every(category => category.protected))
  assert.deepEqual(DEFAULT_PROJECT_STRUCTURE.statuses.map(({ code, name, color, category, sortOrder, protected: protectedLabel }) => [code, name, color, category, sortOrder, protectedLabel]), entries('Status'))
  assert.deepEqual(DEFAULT_PROJECT_STRUCTURE.priorities.map(({ code, name, color, sortOrder }) => [code, name, color, sortOrder]), entries('Priority'))
})
