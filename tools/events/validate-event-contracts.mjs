import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import { parse as parseYaml } from 'yaml'

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '..',
  '..',
)
const eventsRoot = path.join(repositoryRoot, 'contracts', 'events')
const catalogPath = path.join(eventsRoot, 'catalog.yaml')

function fail(message) {
  throw new Error(`事件契约校验失败：${message}`)
}

function assert(condition, message) {
  if (!condition) {
    fail(message)
  }
}

function resolveContractPath(relativePath, expectedExtension) {
  assert(typeof relativePath === 'string' && relativePath.length > 0, '契约路径必须非空')
  const absolute = path.resolve(eventsRoot, relativePath)
  assert(
    absolute.startsWith(`${eventsRoot}${path.sep}`),
    `${relativePath} 逃出 contracts/events`,
  )
  assert(absolute.endsWith(expectedExtension), `${relativePath} 扩展名必须为 ${expectedExtension}`)
  assert(fs.statSync(absolute, { throwIfNoEntry: false })?.isFile(), `${relativePath} 不存在`)
  return absolute
}

function readJson(absolute, label) {
  try {
    return JSON.parse(fs.readFileSync(absolute, 'utf8'))
  } catch (error) {
    fail(`${label} 不是有效 JSON：${error instanceof Error ? error.message : error}`)
  }
}

const catalog = parseYaml(fs.readFileSync(catalogPath, 'utf8'))
assert(catalog?.catalogVersion === 1, 'catalogVersion 必须为 1')
assert(Array.isArray(catalog.events) && catalog.events.length > 0, 'events 必须为非空数组')

const envelopePath = resolveContractPath(
  'schemas/event-envelope.schema.json',
  '.schema.json',
)
const envelopeSchema = readJson(envelopePath, 'event envelope schema')
const ajv = new Ajv({ allErrors: true, strict: true, schemas: [envelopeSchema] })
addFormats(ajv)
for (const fileName of fs.readdirSync(path.join(eventsRoot, 'schemas'))
  .filter(fileName => fileName.endsWith('-payload.schema.json'))) {
  ajv.addSchema(readJson(path.join(eventsRoot, 'schemas', fileName), fileName))
}
const eventKeys = new Set()
const referencedSchemas = new Set()
const referencedExamples = new Set()
let validCount = 0
let invalidCount = 0

for (const event of catalog.events) {
  assert(
    typeof event.eventType === 'string' &&
      /^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(event.eventType),
    'eventType 格式不合法',
  )
  assert(Number.isInteger(event.eventVersion) && event.eventVersion > 0, `${event.eventType} 版本不合法`)
  const key = `${event.eventType}@${event.eventVersion}`
  assert(!eventKeys.has(key), `${key} 重复登记`)
  eventKeys.add(key)

  const schemaPath = resolveContractPath(event.schema, '.schema.json')
  assert(!referencedSchemas.has(schemaPath), `${event.schema} 被多个事件重复引用`)
  referencedSchemas.add(schemaPath)
  const schema = readJson(schemaPath, `${key} schema`)
  const validate = ajv.compile(schema)

  assert(Array.isArray(event.validExamples) && event.validExamples.length > 0, `${key} 缺少合法样例`)
  assert(Array.isArray(event.invalidExamples) && event.invalidExamples.length > 0, `${key} 缺少非法样例`)
  for (const relative of event.validExamples) {
    const absolute = resolveContractPath(relative, '.json')
    assert(!referencedExamples.has(absolute), `${relative} 被重复引用`)
    referencedExamples.add(absolute)
    const value = readJson(absolute, relative)
    if (!validate(value)) {
      fail(`${relative} 应合法但未通过 ${key}：${ajv.errorsText(validate.errors)}`)
    }
    assert(value.eventType === event.eventType, `${relative} eventType 与目录不一致`)
    assert(value.eventVersion === event.eventVersion, `${relative} eventVersion 与目录不一致`)
    validCount += 1
  }
  for (const relative of event.invalidExamples) {
    const absolute = resolveContractPath(relative, '.json')
    assert(!referencedExamples.has(absolute), `${relative} 被重复引用`)
    referencedExamples.add(absolute)
    const value = readJson(absolute, relative)
    assert(!validate(value), `${relative} 应被 ${key} 拒绝`)
    invalidCount += 1
  }
}

console.log(
  `已校验 ${eventKeys.size} 个事件版本、${validCount} 个合法样例和 ${invalidCount} 个非法样例。`,
)
