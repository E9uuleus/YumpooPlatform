import fs from 'node:fs'
import path from 'node:path'
import Ajv from 'ajv'
import addFormats from 'ajv-formats'
import { parse as parseYaml } from 'yaml'

export const freezeManifestPath = 'contracts/events/freeze/workitem-m2-v1.json'
export const eventEnvelopePath = 'schemas/event-envelope.schema.json'

const frozenEventCount = 14
const eventTypePattern = /^workitem\.work_item_[a-z0-9_]+$/u
const allowedProducers = new Set([
  'WorkItemService',
  'WorkItemUpdateService',
  'WorkItemRelationService',
])

export function fail(message) {
  throw new Error(`事件兼容性检查失败：${message}`)
}

export function assert(condition, message) {
  if (!condition) fail(message)
}

export function readJson(file, label = path.basename(file)) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'))
  } catch (error) {
    fail(`${label} 不是有效 JSON：${error instanceof Error ? error.message : error}`)
  }
}

export function readFreezeManifest(repositoryRoot) {
  const manifest = readJson(path.join(repositoryRoot, freezeManifestPath), 'M2-23 冻结清单')
  validateFreezeManifest(manifest)
  return manifest
}

export function validateFreezeManifest(manifest) {
  assert(manifest?.schemaVersion === 1, '冻结清单 schemaVersion 必须为 1')
  assert(manifest.milestone === 'M2-23', '冻结清单 milestone 必须为 M2-23')
  assert(manifest.compatibilityPolicy === 'OPTIONAL_FIELDS_ONLY', '冻结清单兼容策略必须为 OPTIONAL_FIELDS_ONLY')
  assert(manifest.consumerPolicy?.requiresCurrentAuthorization === true, '消费者必须重新校验当前授权')
  assert(manifest.consumerPolicy?.notificationSafePayload === 'TARGET_REFERENCES_ONLY', '通知安全载荷必须只使用目标引用')
  assert(manifest.consumerPolicy?.crossProjectRelationProjection === 'CURRENT_ENDPOINT_ONLY', '跨项目关系必须按当前端点投影')
  assert(Array.isArray(manifest.consumerPolicy?.forbiddenNotificationSources), '冻结清单缺少通知禁用来源')
  assert(Array.isArray(manifest.events) && manifest.events.length === frozenEventCount, `冻结清单必须恰好包含 ${frozenEventCount} 个事件`)

  const keys = new Set()
  for (const event of manifest.events) {
    assert(eventTypePattern.test(event.eventType), `冻结事件名不合法：${event.eventType}`)
    assert(event.eventVersion === 1, `${event.eventType} 必须冻结 v1`)
    assert(['WorkItem', 'WorkItemUpdate', 'WorkItemRelation'].includes(event.aggregateType), `${event.eventType} 聚合类型不合法`)
    assert(typeof event.schema === 'string' && event.schema.startsWith('schemas/') && event.schema.endsWith('.schema.json'), `${event.eventType} Schema 路径不合法`)
    const key = eventKey(event)
    assert(!keys.has(key), `${key} 在冻结清单中重复`)
    keys.add(key)
    assert(Array.isArray(event.producers) && event.producers.length > 0, `${key} 缺少生产者`)
    event.producers.forEach((producer) => assert(allowedProducers.has(producer), `${key} 含未知生产者 ${producer}`))
    assert(event.activityConsumer === 'audit-activity-v1', `${key} Activity 消费者必须为 audit-activity-v1`)
    assert(['NONE', 'ASSIGNEE', 'MENTIONED_USERS'].includes(event.notificationTrigger), `${key} 通知触发器不合法`)
    assert(Array.isArray(event.targetReferenceFields) && event.targetReferenceFields.length > 0, `${key} 缺少目标引用`)
    assert(Array.isArray(event.recipientReferenceFields), `${key} 接收者引用必须为数组`)
    if (event.notificationTrigger === 'NONE') assert(event.recipientReferenceFields.length === 0, `${key} 不触发通知时不得声明接收者引用`)
    if (event.notificationTrigger === 'ASSIGNEE') assert(equalStringSets(event.recipientReferenceFields, ['assigneeUserId']), `${key} 指派接收者必须为 assigneeUserId`)
    if (event.notificationTrigger === 'MENTIONED_USERS') assert(equalStringSets(event.recipientReferenceFields, ['mentionedUserIds']), `${key} Mention 接收者必须为 mentionedUserIds`)
    const relation = event.aggregateType === 'WorkItemRelation'
    assert(relation ? event.scopeIsolation === 'PROJECT_ENDPOINT' : event.scopeIsolation === undefined, `${key} 关系投影隔离声明不正确`)
  }
}

export function loadCurrentBundle(repositoryRoot, manifest = readFreezeManifest(repositoryRoot)) {
  const eventsRoot = path.join(repositoryRoot, 'contracts', 'events')
  const catalog = parseYaml(fs.readFileSync(path.join(eventsRoot, 'catalog.yaml'), 'utf8'))
  const envelopeSchema = readJson(path.join(eventsRoot, eventEnvelopePath), eventEnvelopePath)
  const events = manifest.events.map((frozen) => {
    const catalogEvent = findCatalogEvent(catalog, frozen)
    assert(catalogEvent.schema === frozen.schema, `${eventKey(frozen)} 目录 Schema 与冻结清单不一致`)
    const schema = readJson(resolveEventsPath(eventsRoot, catalogEvent.schema), catalogEvent.schema)
    const validExamples = catalogEvent.validExamples.map((relative) => ({
      path: relative,
      value: readJson(resolveEventsPath(eventsRoot, relative), relative),
    }))
    validateManifestReferences(frozen, schema)
    return {
      eventType: frozen.eventType,
      eventVersion: frozen.eventVersion,
      schemaPath: catalogEvent.schema,
      schema,
      validExamples,
    }
  })
  return { schemaVersion: 1, envelopeSchema, events }
}

export function assertEventContractsCompatible(baseline, current) {
  assert(baseline?.schemaVersion === 1, '历史基线 schemaVersion 必须为 1')
  assert(current?.schemaVersion === 1, '当前契约 bundle schemaVersion 必须为 1')
  compareObjectSchema('事件信封', baseline.envelopeSchema, current.envelopeSchema)

  const currentByKey = new Map(current.events.map((event) => [eventKey(event), event]))
  for (const previous of baseline.events) {
    const key = eventKey(previous)
    const next = currentByKey.get(key)
    assert(next, `${key} 已从当前事件目录移除`)
    compareEvent(key, previous, next, current.envelopeSchema)
  }
}

function compareEvent(key, previous, next, currentEnvelope) {
  const previousLayer = eventLayer(previous.schema, key)
  const nextLayer = eventLayer(next.schema, key)
  assert(nextLayer.properties.eventType?.const === previousLayer.properties.eventType?.const, `${key} eventType 被改变`)
  assert(nextLayer.properties.eventVersion?.const === previousLayer.properties.eventVersion?.const, `${key} eventVersion 被改变`)
  assert(nextLayer.properties.aggregateType?.const === previousLayer.properties.aggregateType?.const, `${key} aggregateType 被改变`)
  assert(canonical(nextLayer.properties.aggregateVersion) === canonical(previousLayer.properties.aggregateVersion), `${key} aggregateVersion 约束被改变`)
  compareObjectSchema(`${key} payload`, previousLayer.properties.payload, nextLayer.properties.payload)

  const ajv = new Ajv({ allErrors: true, strict: true, schemas: [currentEnvelope] })
  addFormats(ajv)
  const validate = ajv.compile(next.schema)
  for (const example of previous.validExamples) {
    assert(validate(example.value), `${key} 不再兼容历史样例 ${example.path}：${ajv.errorsText(validate.errors)}`)
  }
}

function compareObjectSchema(label, previous, next) {
  assert(previous?.type === 'object' && next?.type === 'object', `${label} 必须保持 object`)
  assert(previous.additionalProperties === false && next.additionalProperties === false, `${label} 必须保持 additionalProperties=false`)
  const previousRequired = sortedStrings(previous.required ?? [])
  const nextRequired = sortedStrings(next.required ?? [])
  assert(JSON.stringify(previousRequired) === JSON.stringify(nextRequired), `${label} required 字段集合被改变`)
  const previousProperties = previous.properties ?? {}
  const nextProperties = next.properties ?? {}
  for (const [name, definition] of Object.entries(previousProperties)) {
    assert(Object.hasOwn(nextProperties, name), `${label} 删除了既有字段 ${name}`)
    assert(canonical(definition) === canonical(nextProperties[name]), `${label} 改变了既有字段 ${name} 的约束`)
  }
  for (const name of Object.keys(nextProperties)) {
    if (!Object.hasOwn(previousProperties, name)) {
      assert(!nextRequired.includes(name), `${label} 新字段 ${name} 必须为可选字段`)
    }
  }
}

function validateManifestReferences(frozen, schema) {
  const layer = eventLayer(schema, eventKey(frozen))
  assert(layer.properties.eventType?.const === frozen.eventType, `${eventKey(frozen)} Schema eventType 不一致`)
  assert(layer.properties.eventVersion?.const === frozen.eventVersion, `${eventKey(frozen)} Schema eventVersion 不一致`)
  assert(layer.properties.aggregateType?.const === frozen.aggregateType, `${eventKey(frozen)} Schema aggregateType 不一致`)
  const payloadProperties = layer.properties.payload?.properties ?? {}
  for (const field of [...frozen.targetReferenceFields, ...frozen.recipientReferenceFields]) {
    assert(Object.hasOwn(payloadProperties, field), `${eventKey(frozen)} Schema 缺少冻结引用 ${field}`)
  }
}

function findCatalogEvent(catalog, frozen) {
  assert(Array.isArray(catalog?.events), '事件目录 events 必须为数组')
  const matches = catalog.events.filter((event) => event.eventType === frozen.eventType && event.eventVersion === frozen.eventVersion)
  assert(matches.length === 1, `${eventKey(frozen)} 必须在事件目录中恰好登记一次`)
  const event = matches[0]
  assert(Array.isArray(event.validExamples) && event.validExamples.length > 0, `${eventKey(frozen)} 缺少合法样例`)
  return event
}

function eventLayer(schema, key) {
  assert(Array.isArray(schema?.allOf), `${key} Schema 必须使用 allOf 组合信封`)
  assert(schema.allOf.some((entry) => entry.$ref === 'event-envelope.schema.json'), `${key} Schema 必须引用稳定事件信封`)
  const layers = schema.allOf.filter((entry) => entry?.properties?.eventType)
  assert(layers.length === 1, `${key} Schema 必须恰好定义一个事件层`)
  assert(layers[0].properties?.payload, `${key} Schema 缺少 payload`)
  return layers[0]
}

function resolveEventsPath(eventsRoot, relative) {
  assert(typeof relative === 'string' && relative.length > 0, '事件契约路径不能为空')
  const absolute = path.resolve(eventsRoot, relative)
  assert(absolute.startsWith(`${eventsRoot}${path.sep}`), `${relative} 逃出 contracts/events`)
  assert(fs.statSync(absolute, { throwIfNoEntry: false })?.isFile(), `${relative} 不存在`)
  return absolute
}

function eventKey(event) {
  return `${event.eventType}@${event.eventVersion}`
}

function sortedStrings(values) {
  assert(Array.isArray(values) && values.every((value) => typeof value === 'string'), 'Schema 字段集合必须为字符串数组')
  return [...values].sort((left, right) => left.localeCompare(right, 'en'))
}

function equalStringSets(left, right) {
  return JSON.stringify(sortedStrings(left)) === JSON.stringify(sortedStrings(right))
}

function canonical(value, key = '') {
  if (Array.isArray(value)) {
    const entries = value.map((entry) => canonical(entry))
    if (['enum', 'required', 'type'].includes(key)) entries.sort((left, right) => left.localeCompare(right, 'en'))
    return `[${entries.join(',')}]`
  }
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort((left, right) => left.localeCompare(right, 'en'))
      .map((name) => `${JSON.stringify(name)}:${canonical(value[name], name)}`).join(',')}}`
  }
  return JSON.stringify(value)
}
