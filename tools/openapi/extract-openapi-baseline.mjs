import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  assertDelivery,
  atomicWriteJson,
  gitObject,
  resolveGitCommit,
  sha256Buffer,
} from '../verification/delivery-utils.mjs'

export function extractOpenApiBaseline({ repositoryRoot, reference, outputPath, metadataPath }) {
  const baseCommit = resolveGitCommit(repositoryRoot, reference)
  const contents = gitObject(repositoryRoot, baseCommit, 'contracts/openapi/yumpoo-v1.yaml')
  assertDelivery(contents.trim().length > 0, 'OpenAPI 基线文件为空')
  fs.mkdirSync(path.dirname(outputPath), { recursive: true })
  const temporary = `${outputPath}.tmp-${process.pid}-${Date.now()}`
  fs.writeFileSync(temporary, contents, { encoding: 'utf8', mode: 0o600, flag: 'wx' })
  fs.renameSync(temporary, outputPath)
  const metadata = {
    schemaVersion: 1,
    milestone: 'DELIVERY',
    baseReference: reference,
    baseCommit,
    openApiPath: 'contracts/openapi/yumpoo-v1.yaml',
    bytes: Buffer.byteLength(contents, 'utf8'),
    sha256: sha256Buffer(contents),
  }
  atomicWriteJson(metadataPath, metadata)
  return metadata
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)
if (invokedDirectly) {
  const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  const reference = process.argv[2] ?? process.env.YUMPOO_CI_BASE_REF ?? 'origin/dev'
  const outputPath = path.resolve(
    process.argv[3] ??
      process.env.YUMPOO_CI_BASELINE_PATH ??
      path.join(repositoryRoot, 'out', 'contracts', 'openapi-baseline.yaml'),
  )
  const metadataPath = path.resolve(
    process.argv[4] ??
      process.env.YUMPOO_CI_BASELINE_METADATA_PATH ??
      path.join(repositoryRoot, 'out', 'contracts', 'openapi-baseline.metadata.json'),
  )
  const metadata = extractOpenApiBaseline({ repositoryRoot, reference, outputPath, metadataPath })
  console.log(`DELIVERY OpenAPI 基线已从 ${metadata.baseCommit} 提取并校验：${metadata.sha256}`)
}
