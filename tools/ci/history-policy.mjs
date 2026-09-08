import fs from 'node:fs'
import path from 'node:path'
import { spawnSync } from 'node:child_process'
import { resolveGitCommit } from '../verification/m0-18-utils.mjs'

function git(root, args) {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (result.status !== 0) throw new Error(`历史基线不可读取：git ${args[0]}（${result.status ?? result.error?.code}）`)
  return result.stdout
}

const normalize = text => text.replaceAll('\r\n', '\n')

export function checkHistory(root, reference) {
  let baseCommit
  try {
    baseCommit = resolveGitCommit(root, reference)
  } catch (error) {
    throw new Error(`历史基线不可读取：${error.message}`, { cause: error })
  }
  const files = git(root, ['ls-tree', '-r', '--name-only', '-z', baseCommit, '--',
    'backend/src/main/resources/db/migration', 'contracts/events/freeze']).split('\0').filter(Boolean)
  if (!files.some(file => file.endsWith('.sql'))) throw new Error('历史基线缺少数据库迁移，拒绝空基线')
  for (const file of files) {
    const current = path.join(root, file)
    if (!fs.statSync(current, { throwIfNoEntry: false })?.isFile()
      || normalize(fs.readFileSync(current, 'utf8')) !== normalize(git(root, ['show', `${baseCommit}:${file}`]))) {
      throw new Error(`已交付历史不得修改、移动或删除：${file}；新增前向迁移或新版本契约`)
    }
  }
  const latest = Math.max(...files.filter(file => file.endsWith('.sql')).map(file => Number(path.basename(file).match(/^V(\d+)__/u)?.[1])))
  const currentMigrations = git(root, ['ls-files', '-c', '-o', '--exclude-standard', '-z', '--',
    'backend/src/main/resources/db/migration']).split('\0').filter(file => file.endsWith('.sql'))
  const versions = new Set()
  for (const file of currentMigrations) {
    const version = Number(path.basename(file).match(/^V(\d+)__[a-z0-9_]+\.sql$/u)?.[1])
    if (!Number.isSafeInteger(version) || version < 1 || versions.has(version)
      || (!files.includes(file) && version <= latest)) {
      throw new Error(`迁移必须使用唯一版本并向前追加：${file}`)
    }
    versions.add(version)
  }
  const manifestPath = 'tools/openapi/breaking-change-exceptions.json'
  const baseline = JSON.parse(git(root, ['show', `${baseCommit}:${manifestPath}`]))
  const current = JSON.parse(fs.readFileSync(path.join(root, manifestPath), 'utf8'))
  for (const entry of baseline.exceptions) {
    if (JSON.stringify(current.exceptions.find(item => item.id === entry.id)) !== JSON.stringify(entry)) {
      throw new Error(`不得改写既有 OpenAPI 精确例外：${entry.id}`)
    }
  }
  return baseCommit
}
