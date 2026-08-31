import assert from 'node:assert/strict'
import test from 'node:test'
import { assertEventContractsCompatible } from './event-contract-compat.mjs'

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
