import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  assertExactPayload,
  assertDelivery,
  assertSchema,
  gitHead,
  readJson,
  sha256GitTextFile,
} from './delivery-utils.mjs'

export function verifyPortableHandoff(repositoryRoot, handoffRoot, options = {}) {
  const manifestPath = path.join(handoffRoot, 'portable-handoff.json')
  assertDelivery(fs.statSync(manifestPath, { throwIfNoEntry: false })?.isFile(), 'portable handoff manifest 不存在')
  const manifest = readJson(manifestPath)
  assertSchema(
    path.join(repositoryRoot, 'tools', 'verification', 'schemas', 'portable-handoff.schema.json'),
    manifest,
    'portable-handoff.json',
  )
  const expectedCommit = options.expectedCommit ?? gitHead(repositoryRoot)
  assertDelivery(manifest.testedCommit === expectedCommit, 'portable handoff testedCommit 与当前 checkout 不一致')
  assertDelivery(
    manifest.currentOpenApiSha256 ===
      sha256GitTextFile(
        repositoryRoot,
        expectedCommit,
        'contracts/openapi/yumpoo-v1.yaml',
        path.join(repositoryRoot, 'contracts', 'openapi', 'yumpoo-v1.yaml'),
      ),
    'portable handoff 当前 OpenAPI 摘要不一致',
  )
  assertExactPayload(handoffRoot, manifest, 'portable-handoff.json', expectedCommit)
  return manifest
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invokedDirectly) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  const handoffArgument = process.argv.slice(2).find((argument) => argument !== '--')
  const handoffRoot = path.resolve(
    handoffArgument ??
      process.env.YUMPOO_HANDOFF_ROOT ??
      path.join(repositoryRoot, 'out', 'handoff'),
  )
  const manifest = verifyPortableHandoff(repositoryRoot, handoffRoot)
  console.log(`DELIVERY portable handoff 已复核：${manifest.sourceCommit}`)
}
