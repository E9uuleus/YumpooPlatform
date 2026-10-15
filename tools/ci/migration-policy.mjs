import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { resolveGitCommit } from '../verification/m0-18-utils.mjs'

export const migrationRoot = 'backend/src/main/resources/db/migration'
export const exceptionsPath = 'contracts/migrations/rollback-safety-exceptions.json'
const migrationFile = /^(?:[a-z][a-z0-9]*\/)?V(\d+)__[a-z0-9_]+\.sql$/u
const identifier = String.raw`(?:"[^"]+"|[A-Za-z_][\w$]*)(?:\s*\.\s*(?:"[^"]+"|[A-Za-z_][\w$]*))?`

const tableName = raw => raw.split('.').pop().replaceAll('"', '').trim().toLowerCase()
const normalize = text => text.replaceAll('\r\n', '\n')
export const migrationDigest = text => createHash('sha256').update(normalize(text)).digest('hex')

export function splitStatements(sql) {
  const statements = []
  let buffer = ''
  let index = 0
  while (index < sql.length) {
    const char = sql[index]
    const next = sql[index + 1]
    if (char === '-' && next === '-') {
      const end = sql.indexOf('\n', index)
      index = end < 0 ? sql.length : end
      buffer += ' '
    } else if (char === '/' && next === '*') {
      const end = sql.indexOf('*/', index + 2)
      if (end < 0) throw new Error('块注释未闭合')
      index = end + 2
      buffer += ' '
    } else if (char === "'" || char === '"') {
      let end = index + 1
      for (;;) {
        end = sql.indexOf(char, end)
        if (end < 0) throw new Error('引号未闭合')
        if (sql[end + 1] !== char) break
        end += 2
      }
      buffer += sql.slice(index, end + 1)
      index = end + 1
    } else if (char === '$' && /^\$[A-Za-z_]*\$/u.test(sql.slice(index, index + 64))) {
      const tag = sql.slice(index, index + 64).match(/^\$[A-Za-z_]*\$/u)[0]
      const end = sql.indexOf(tag, index + tag.length)
      if (end < 0) throw new Error('dollar quote 未闭合')
      buffer += sql.slice(index, end + tag.length)
      index = end + tag.length
    } else if (char === ';') {
      if (buffer.trim()) statements.push(buffer.trim())
      buffer = ''
      index += 1
    } else {
      buffer += char
      index += 1
    }
  }
  if (buffer.trim()) statements.push(buffer.trim())
  return statements
}

function splitTopLevel(text) {
  const parts = []
  let depth = 0
  let start = 0
  for (let index = 0; index < text.length; index += 1) {
    if (text[index] === '(') depth += 1
    else if (text[index] === ')') depth -= 1
    else if (text[index] === ',' && depth === 0) {
      parts.push(text.slice(start, index))
      start = index + 1
    }
  }
  parts.push(text.slice(start))
  return parts.map(part => part.trim()).filter(Boolean)
}

function safeAddColumn(action) {
  if (!/^ADD (?:COLUMN )?(?:IF NOT EXISTS )?/iu.test(action)) return false
  if (/^ADD (?:CONSTRAINT|PRIMARY KEY|UNIQUE|FOREIGN KEY|CHECK|EXCLUDE)\b/iu.test(action)) return false
  if (/\bPRIMARY KEY\b/iu.test(action)) return false
  return !/\bNOT NULL\b/iu.test(action) || /\bDEFAULT\b/iu.test(action)
}

// 只扩展 = 上一版本代码在新 schema 上仍能读写；白名单之外一律按破坏性处理。
export function classifyStatement(statement, created) {
  const sql = statement.replace(/\s+/gu, ' ').trim()
  const brief = sql.length > 96 ? `${sql.slice(0, 96)}…` : sql
  let match = sql.match(new RegExp(String.raw`^CREATE (?:UNLOGGED )?TABLE (?:IF NOT EXISTS )?(${identifier})`, 'iu'))
  if (match) {
    created.add(tableName(match[1]))
    return null
  }
  match = sql.match(new RegExp(String.raw`^CREATE (UNIQUE )?INDEX (?:CONCURRENTLY )?(?:IF NOT EXISTS )?(?:${identifier} )?ON (?:ONLY )?(${identifier})`, 'iu'))
  if (match) return !match[1] || created.has(tableName(match[2])) ? null : `已有表新增唯一索引可能拒绝旧版本写入：${brief}`
  if (/^(?:CREATE (?:SEQUENCE|TYPE|VIEW|FUNCTION) |COMMENT ON |GRANT |SET )/iu.test(sql)) return null
  match = sql.match(new RegExp(String.raw`^ALTER TABLE (?:IF EXISTS )?(?:ONLY )?(${identifier}) (.+)$`, 'iu'))
  if (match) {
    if (created.has(tableName(match[1]))) return null
    const unsafe = splitTopLevel(match[2]).find(action => !safeAddColumn(action))
    return unsafe ? `已有表的结构变更不保证旧版本兼容：${brief}` : null
  }
  match = sql.match(new RegExp(String.raw`^(?:INSERT INTO |UPDATE (?:ONLY )?|DELETE FROM (?:ONLY )?)(${identifier})`, 'iu'))
  if (match) return created.has(tableName(match[1])) ? null : `修改已有表数据，旧版本可能依赖原有数据形态：${brief}`
  return `不在只扩展白名单内：${brief}`
}

export function classifyMigration(sql) {
  let statements
  try {
    statements = splitStatements(sql)
  } catch (error) {
    return { classification: 'breaking', reasons: [`无法解析迁移：${error.message}`] }
  }
  const created = new Set()
  const reasons = statements.map(statement => classifyStatement(statement, created)).filter(Boolean)
  return { classification: reasons.length ? 'breaking' : 'expand', reasons }
}

export function validateRollbackExceptions(value, root) {
  if (value?.schemaVersion !== 1 || !Array.isArray(value.exceptions)) throw new Error('迁移回退例外清单结构无效')
  const files = new Set()
  for (const entry of value.exceptions) {
    const keys = Object.keys(entry).sort().join(',')
    if (!['classification,file,reason,sha256', 'agentNote,classification,file,reason,sha256'].includes(keys)
      || !migrationFile.test(entry.file) || files.has(entry.file)
      || !/^[0-9a-f]{64}$/u.test(entry.sha256)
      || !['expand', 'breaking'].includes(entry.classification)
      || typeof entry.reason !== 'string' || !entry.reason.trim()) {
      throw new Error(`迁移回退例外条目无效：${entry?.file ?? '<unknown>'}`)
    }
    if (entry.classification === 'breaking'
      && (typeof entry.agentNote !== 'string' || !/^\.agents\/notes\/implemented\/.+\.md$/u.test(entry.agentNote)
        || !fs.existsSync(path.join(root, entry.agentNote)))) {
      throw new Error(`破坏性迁移必须链接已实施的 Agent Note：${entry.file}`)
    }
    files.add(entry.file)
  }
  return value
}

export function readRollbackExceptions(root) {
  const file = path.join(root, exceptionsPath)
  return validateRollbackExceptions(JSON.parse(fs.readFileSync(file, 'utf8')), root)
}

function git(root, args) {
  const result = spawnSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 32 * 1024 * 1024 })
  if (result.status !== 0) throw new Error(`git ${args[0]} 失败：${(result.stderr || '').trim()}`)
  return result.stdout
}

function currentMigrationFiles(root) {
  return git(root, ['ls-files', '-c', '-o', '--exclude-standard', '-z', '--', migrationRoot])
    .split('\0').filter(file => file.endsWith('.sql'))
    .filter(file => fs.existsSync(path.join(root, file)))
    .map(file => file.slice(migrationRoot.length + 1))
}

function versionOf(file) {
  return Number(file.match(migrationFile)?.[1])
}

export function migrationInventory(root, exceptions = readRollbackExceptions(root)) {
  const overrides = new Map(exceptions.exceptions.map(entry => [entry.file, entry]))
  return currentMigrationFiles(root).sort((left, right) => versionOf(left) - versionOf(right)).map(file => {
    const text = fs.readFileSync(path.join(root, migrationRoot, file), 'utf8')
    const sha256 = migrationDigest(text)
    const override = overrides.get(file)
    const detected = classifyMigration(text)
    return {
      version: versionOf(file), file, sha256,
      classification: override?.sha256 === sha256 ? override.classification : detected.classification,
      reasons: detected.reasons,
    }
  })
}

export function evaluateNewMigrations(root, baseCommit) {
  const exceptions = readRollbackExceptions(root)
  const baseline = git(root, ['ls-tree', '-r', '--name-only', '-z', baseCommit, '--', exceptionsPath]).includes(exceptionsPath)
    ? JSON.parse(git(root, ['show', `${baseCommit}:${exceptionsPath}`])) : { schemaVersion: 1, exceptions: [] }
  for (const [index, entry] of baseline.exceptions.entries()) {
    if (JSON.stringify(exceptions.exceptions[index]) !== JSON.stringify(entry)) {
      throw new Error(`迁移回退例外清单只允许追加：${entry.file}`)
    }
  }
  const delivered = new Set(git(root, ['ls-tree', '-r', '--name-only', '-z', baseCommit, '--', migrationRoot])
    .split('\0').filter(Boolean).map(file => file.slice(migrationRoot.length + 1)))
  const inventory = migrationInventory(root, exceptions)
  for (const entry of exceptions.exceptions) {
    const current = inventory.find(item => item.file === entry.file)
    if (!current || current.sha256 !== entry.sha256) throw new Error(`迁移回退例外与文件内容不一致：${entry.file}`)
  }
  const overrides = new Set(exceptions.exceptions.map(entry => entry.file))
  const added = inventory.filter(item => !delivered.has(item.file))
  const violations = added
    .filter(item => item.reasons.length && !overrides.has(item.file))
    .map(item => `${item.file} 不是只扩展迁移：\n  - ${item.reasons.join('\n  - ')}`)
  return { added, violations }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  try {
    const { added, violations } = evaluateNewMigrations(root, resolveGitCommit(root, process.argv[2] || 'origin/dev'))
    for (const item of added) console.log(`${item.file}: ${item.classification}`)
    if (violations.length) {
      throw new Error(`新增迁移必须只扩展，以保证上一版本可快速回退。拆分为 expand/contract 两次发布，或登记到 ${exceptionsPath}：\n${violations.join('\n')}`)
    }
    console.log(`新增迁移回退安全检查通过（${added.length} 个）`)
  } catch (error) {
    console.error(error.message)
    process.exitCode = 1
  }
}
