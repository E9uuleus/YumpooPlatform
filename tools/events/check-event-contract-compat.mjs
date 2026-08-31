import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  assert,
  assertEventContractsCompatible,
  loadCurrentBundle,
  readFreezeManifest,
  readJson,
} from './event-contract-compat.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const baselineArgument = process.argv.slice(2).find((argument) => argument !== '--')
assert(baselineArgument, '必须显式传入历史基线：pnpm check:event-contract-compat -- <baseline>')
const baselinePath = path.resolve(repositoryRoot, baselineArgument)
assert(fs.statSync(baselinePath, { throwIfNoEntry: false })?.isFile(), `事件契约基线不存在：${baselinePath}`)

const manifest = readFreezeManifest(repositoryRoot)
const baseline = readJson(baselinePath, '事件契约历史基线')
const current = loadCurrentBundle(repositoryRoot, manifest)
assertEventContractsCompatible(baseline, current)
console.log(`M2-23 已验证 ${manifest.events.length} 个冻结事件与历史基线兼容。`)
