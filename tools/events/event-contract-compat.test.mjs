import assert from 'node:assert/strict'
import test from 'node:test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { assertEventContractsCompatible, loadCurrentBundle } from './event-contract-compat.mjs'
import { assertRegisteredInventory } from './event-inventory-policy.mjs'

const envelope = {
  $id: 'event-envelope.schema.json',
  type: 'object',
  additionalProperties: false,
  required: ['eventId', 'eventType', 'eventVersion', 'aggregateType', 'aggregateId', 'aggregateVersion', 'payload'],
  properties: {
    eventId: { type: 'string', format: 'uuid' },
    eventType: { type: 'string' },
    eventVersion: { type: 'integer', minimum: 1 },
    aggregateType: { type: 'string' },
    aggregateId: { type: 'string', format: 'uuid' },
    aggregateVersion: { type: 'integer', minimum: 1 },
    payload: { type: 'object' },
  },
}

function eventSchema({
  eventType = 'workitem.work_item_created',
  eventVersion = 1,
  aggregateType = 'WorkItem',
  aggregateVersion = { type: 'integer', minimum: 1 },
  required = ['workItemId', 'projectId'],
  properties = {
    workItemId: { type: 'string', format: 'uuid' },
    projectId: { type: 'string', format: 'uuid' },
  },
  additionalProperties = false,
} = {}) {
  return {
    allOf: [
      { $ref: 'event-envelope.schema.json' },
      {
        type: 'object',
        properties: {
          eventType: { const: eventType },
          eventVersion: { const: eventVersion },
          aggregateType: { const: aggregateType },
          aggregateVersion,
          payload: { type: 'object', additionalProperties, required, properties },
        },
      },
    ],
  }
}

function validExample(eventType = 'workitem.work_item_created', eventVersion = 1) {
  return {
    eventId: '11111111-1111-4111-8111-111111111111',
    eventType,
    eventVersion,
    aggregateType: 'WorkItem',
    aggregateId: '22222222-2222-4222-8222-222222222222',
    aggregateVersion: 1,
    payload: {
      workItemId: '22222222-2222-4222-8222-222222222222',
      projectId: '33333333-3333-4333-8333-333333333333',
    },
  }
}

function eventEntry(schema = eventSchema(), overrides = {}) {
  return {
    eventType: 'workitem.work_item_created',
    eventVersion: 1,
    schemaPath: 'schemas/workitem.work_item_created.v1.schema.json',
    schema,
    validExamples: [{ path: 'examples/valid/workitem.work_item_created.v1.json', value: validExample() }],
    ...overrides,
  }
}

function bundle(event = eventEntry()) {
  return { schemaVersion: 1, envelopeSchema: structuredClone(envelope), events: [event] }
}

function compatible(current) {
  assert.doesNotThrow(() => assertEventContractsCompatible(bundle(), current))
}

function incompatible(current, pattern) {
  assert.throws(() => assertEventContractsCompatible(bundle(), current), pattern)
}

test('原契约保持兼容', () => compatible(bundle()))

test('当前事件库存可以登记新增事件，未登记生产者事件和丢失冻结核心仍失败', () => {
  const frozen = new Set(['core'])
  const registered = new Set(['core', 'additional'])
  assert.doesNotThrow(() => assertRegisteredInventory('producer', new Set(['core', 'additional']), frozen, registered))
  assert.throws(() => assertRegisteredInventory('producer', new Set(['core', 'unknown']), frozen, registered), /未登记事件=\[unknown\]/u)
  assert.throws(() => assertRegisteredInventory('producer', new Set(['additional']), frozen, registered), /缺少冻结事件=\[core\]/u)
})

test('注释调整与嵌套可选字段演进通过，对象约束收紧仍失败', () => {
  const baseline = bundle()
  baseline.events[0].schema.allOf[1].properties.payload.properties.metadata = {
    type: 'object', additionalProperties: false, properties: { description: { type: 'string' } },
  }
  const current = structuredClone(baseline)
  const payload = current.events[0].schema.allOf[1].properties.payload
  payload.description = '说明可更新'
  payload.properties.metadata.properties.optionalNote = { type: 'string' }
  assert.doesNotThrow(() => assertEventContractsCompatible(baseline, current))
  payload.minProperties = 3
  assert.throws(() => assertEventContractsCompatible(baseline, current), /对象约束被改变/u)
  delete payload.minProperties
  payload.properties.metadata.properties.description.maxLength = 3
  assert.throws(() => assertEventContractsCompatible(baseline, current), /description 的约束/u)
})

test('完整目录保护冻结清单以外的 Content、附件和计时版本', () => {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  const baseline = loadCurrentBundle(root)
  assert(baseline.events.some(event => event.eventType === 'workitem.content_deleted' && event.eventVersion === 2))
  assert(baseline.events.some(event => event.eventType.startsWith('filestorage.')))
  const current = structuredClone(baseline)
  assert.doesNotThrow(() => assertEventContractsCompatible(baseline, current))
  current.events = current.events.filter(event => event.eventType !== 'workitem.content_deleted')
  assert.throws(() => assertEventContractsCompatible(baseline, current), /workitem.content_deleted@2 已从当前事件目录移除/u)
})

test('同一 v1 新增可选字段保持兼容', () => {
  const schema = eventSchema({
    properties: {
      workItemId: { type: 'string', format: 'uuid' },
      projectId: { type: 'string', format: 'uuid' },
      correlationHint: { type: 'string', maxLength: 64 },
    },
  })
  compatible(bundle(eventEntry(schema)))
})

test('新增 v2 不阻塞既有 v1', () => {
  const current = bundle()
  current.events.push(eventEntry(eventSchema({ eventVersion: 2 }), {
    eventVersion: 2,
    schemaPath: 'schemas/workitem.work_item_created.v2.schema.json',
    validExamples: [],
  }))
  compatible(current)
})

test('删除或改名既有事件会失败', () => {
  incompatible({ schemaVersion: 1, envelopeSchema: envelope, events: [] }, /已从当前事件目录移除/u)
  incompatible(bundle(eventEntry(eventSchema({ eventType: 'workitem.work_item_created_renamed' }), {
    eventType: 'workitem.work_item_created_renamed',
  })), /已从当前事件目录移除/u)
})

test('改变聚合语义会失败', () => {
  incompatible(bundle(eventEntry(eventSchema({ aggregateType: 'Project' }))), /aggregateType 被改变/u)
  incompatible(bundle(eventEntry(eventSchema({ aggregateVersion: { type: 'integer', minimum: 2 } }))), /aggregateVersion 约束被改变/u)
})

test('增删必填字段都会失败', () => {
  incompatible(bundle(eventEntry(eventSchema({ required: ['workItemId'] }))), /required 字段集合被改变/u)
  incompatible(bundle(eventEntry(eventSchema({
    required: ['workItemId', 'projectId', 'correlationHint'],
    properties: {
      workItemId: { type: 'string', format: 'uuid' },
      projectId: { type: 'string', format: 'uuid' },
      correlationHint: { type: 'string' },
    },
  }))), /required 字段集合被改变/u)
})

test('删除既有字段或放宽封闭对象会失败', () => {
  incompatible(bundle(eventEntry(eventSchema({
    required: ['workItemId', 'projectId'],
    properties: { workItemId: { type: 'string', format: 'uuid' } },
  }))), /删除了既有字段 projectId/u)
  incompatible(bundle(eventEntry(eventSchema({ additionalProperties: true }))), /additionalProperties=false/u)
})

test('改变类型、枚举或约束会失败', () => {
  incompatible(bundle(eventEntry(eventSchema({
    properties: {
      workItemId: { type: 'integer' },
      projectId: { type: 'string', format: 'uuid' },
    },
  }))), /改变了既有字段 workItemId 的约束/u)
  incompatible(bundle(eventEntry(eventSchema({
    properties: {
      workItemId: { type: 'string', format: 'uuid' },
      projectId: { type: 'string', format: 'uuid', enum: ['33333333-3333-4333-8333-333333333333'] },
    },
  }))), /改变了既有字段 projectId 的约束/u)
  incompatible(bundle(eventEntry(eventSchema({
    properties: {
      workItemId: { type: 'string', format: 'uuid', minLength: 1 },
      projectId: { type: 'string', format: 'uuid' },
    },
  }))), /改变了既有字段 workItemId 的约束/u)
})

test('历史合法样例必须继续通过当前 Schema', () => {
  const baseline = bundle(eventEntry(eventSchema(), {
    validExamples: [{
      path: 'examples/valid/historical.json',
      value: { ...validExample(), payload: { ...validExample().payload, optionalLabel: '历史值' } },
    }],
  }))
  const current = bundle(eventEntry(eventSchema({
    properties: {
      workItemId: { type: 'string', format: 'uuid' },
      projectId: { type: 'string', format: 'uuid' },
      optionalLabel: { type: 'integer' },
    },
  })))
  assert.throws(() => assertEventContractsCompatible(baseline, current), /不再兼容历史样例/u)
})
