import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  assertExactPayload,
  assertM018,
  assertSchema,
  gitHead,
  readJson,
  sha256File,
} from './m0-18-utils.mjs'

export function verifyM018Handoff(repositoryRoot, handoffRoot, options = {}) {
  const manifestPath = path.join(handoffRoot, 'portable-handoff.json')
  assertM018(fs.statSync(manifestPath, { throwIfNoEntry: false })?.isFile(), 'portable handoff manifest 不存在')
  const manifest = readJson(manifestPath)
  assertSchema(
    path.join(repositoryRoot, 'evidence', 'm0-18', 'portable-handoff.schema.json'),
    manifest,
    'portable-handoff.json',
  )
  const expectedCommit = options.expectedCommit ?? gitHead(repositoryRoot)
  assertM018(manifest.testedCommit === expectedCommit, 'portable handoff testedCommit 与当前 checkout 不一致')
  assertM018(manifest.currentOpenApiSha256 === sha256File(path.join(repositoryRoot, 'contracts', 'openapi', 'yumpoo-v1.yaml')), 'portable handoff 当前 OpenAPI 摘要不一致')
  assertExactPayload(handoffRoot, manifest, 'portable-handoff.json', expectedCommit)
  return manifest
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invokedDirectly) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  const handoffArgument = process.argv.slice(2).find((argument) => argument !== '--')
  const handoffRoot = path.resolve(
    handoffArgument ??
      process.env.YUMPOO_M018_HANDOFF_ROOT ??
      path.join(repositoryRoot, 'out', 'm0-18', 'portable-handoff'),
  )
  const manifest = verifyM018Handoff(repositoryRoot, handoffRoot)
  console.log(`M0-18 portable handoff 已复核：${manifest.sourceCommit}`)
}
