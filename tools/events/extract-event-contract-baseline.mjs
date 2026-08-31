import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse as parseYaml } from 'yaml'
import {
  assert,
  eventEnvelopePath,
  readFreezeManifest,
} from './event-contract-compat.mjs'
import {
  atomicWriteJson,
  gitObject,
  resolveGitCommit,
} from '../verification/m0-18-utils.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const reference = process.argv[2] ?? process.env.YUMPOO_M223_BASE_REF ?? 'origin/dev'
const outputPath = path.resolve(process.argv[3] ?? path.join(repositoryRoot, 'out', 'm2-23', 'event-contract-baseline.json'))
const manifest = readFreezeManifest(repositoryRoot)
const baseCommit = resolveGitCommit(repositoryRoot, reference)
const catalog = parseYaml(gitObject(repositoryRoot, baseCommit, 'contracts/events/catalog.yaml'))
const envelopeSchema = parseJson(
  gitObject(repositoryRoot, baseCommit, `contracts/events/${eventEnvelopePath}`),
  eventEnvelopePath,
)

const events = manifest.events.map((frozen) => {
  const matches = catalog?.events?.filter((event) =>
    event.eventType === frozen.eventType && event.eventVersion === frozen.eventVersion) ?? []
  assert(matches.length === 1, `${frozen.eventType}@${frozen.eventVersion} 在基线目录中必须恰好登记一次`)
  const catalogEvent = matches[0]
  assert(Array.isArray(catalogEvent.validExamples) && catalogEvent.validExamples.length > 0,
    `${frozen.eventType}@${frozen.eventVersion} 基线缺少合法样例`)
  return {
    eventType: frozen.eventType,
    eventVersion: frozen.eventVersion,
    schemaPath: catalogEvent.schema,
    schema: parseJson(
      gitObject(repositoryRoot, baseCommit, `contracts/events/${catalogEvent.schema}`),
      catalogEvent.schema,
    ),
    validExamples: catalogEvent.validExamples.map((relative) => ({
      path: relative,
      value: parseJson(
        gitObject(repositoryRoot, baseCommit, `contracts/events/${relative}`),
        relative,
      ),
    })),
  }
})

atomicWriteJson(outputPath, {
  schemaVersion: 1,
  milestone: 'M2-23',
  baseReference: reference,
  baseCommit,
  envelopeSchema,
  events,
})
console.log(`M2-23 事件契约基线已从 ${baseCommit} 提取：${outputPath}`)

function parseJson(value, label) {
  try {
    return JSON.parse(value)
  } catch (error) {
    throw new Error(`事件基线 ${label} 不是有效 JSON：${error instanceof Error ? error.message : error}`)
  }
}
