import assert from 'node:assert/strict'
import test from 'node:test'
import path from 'node:path'
import fs from 'node:fs'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { parse as parseYaml, stringify as stringifyYaml } from 'yaml'
import { assertEventContractsCompatible, loadCurrentBundle } from './event-contract-compat.mjs'
import { assertRegisteredInventory } from './event-inventory-policy.mjs'
import { assertRetirementHistory } from '../ci/history-policy.mjs'

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

const retiredEntry = {
  eventType: 'catalog.product_created', eventVersion: 1,
  reason: '统一项目模型取消产品概念',
  agentNote: '.agents/notes/implemented/product/2026-09-30-remove-product-concept.md',
}

test('只允许精确登记的退役版本缺失，其他版本仍受兼容检查保护', () => {
  const baseline = bundle(eventEntry(eventSchema({ eventType: retiredEntry.eventType }), retiredEntry))
  const current = { ...bundle(), events: [], retired: [retiredEntry] }
  assert.doesNotThrow(() => assertEventContractsCompatible(baseline, current))
  assert.throws(() => assertEventContractsCompatible(baseline, { ...current, retired: [] }), /已从当前事件目录移除/u)
  assert.throws(() => assertEventContractsCompatible(baseline,
    { ...current, retired: [{ ...retiredEntry, eventVersion: 2 }] }), /已从当前事件目录移除/u)
})

test('退役事件不可重新登记、保留文件或绕过冻结', () => {
  const baseline = bundle(eventEntry(eventSchema({ eventType: retiredEntry.eventType }), retiredEntry))
  const current = { ...bundle(), events: [], retired: [retiredEntry] }
  assert.throws(() => assertEventContractsCompatible(baseline,
    { ...current, events: baseline.events }), /不得出现在当前事件目录/u)
  assert.throws(() => assertEventContractsCompatible(baseline,
    { ...current, frozenEvents: [retiredEntry] }), /冻结事件，不可退役/u)
  for (const file of [baseline.events[0].schemaPath, baseline.events[0].validExamples[0].path]) {
    assert.throws(() => assertEventContractsCompatible(baseline,
      { ...current, contractFiles: [file] }), /仍保留契约文件/u)
  }
})

test('退役清单只能追加，修改、删除、重排、重复与缺少决策记录均失败', () => {
  const previous = { schemaVersion: 1, retired: [retiredEntry] }
  const additional = { ...retiredEntry, eventVersion: 2 }
  assert.doesNotThrow(() => assertRetirementHistory(previous, { ...previous, retired: [retiredEntry, additional] }))
  for (const retired of [[], [{ ...retiredEntry, reason: '改写' }], [additional, retiredEntry],
    [{ ...retiredEntry, agentNote: retiredEntry.agentNote.replace('/implemented/', '/archived/') }]]) {
    assert.throws(() => assertRetirementHistory(previous, { ...previous, retired }), /只允许追加/u)
  }
  assert.throws(() => assertRetirementHistory(previous, { ...previous, retired: [retiredEntry, retiredEntry] }), /重复/u)
  assert.throws(() => assertRetirementHistory(previous,
    { ...previous, retired: [{ ...retiredEntry, agentNote: '../outside.md' }] }), /决策记录路径/u)
})

function retirementRepository(context) {
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'yumpoo-retirement-'))
  context.after(() => {
    assert(path.resolve(directory).startsWith(path.join(path.resolve(os.tmpdir()), 'yumpoo-retirement-')))
    fs.rmSync(directory, { recursive: true, force: true })
  })
  fs.cpSync(path.join(root, 'contracts/events'), path.join(directory, 'contracts/events'), { recursive: true })
  const fixture = { ...retiredEntry, eventType: 'catalog.retirement_fixture' }
  const manifest = JSON.parse(fs.readFileSync(path.join(root, 'tools/events/retired-event-contracts.json'), 'utf8'))
  manifest.retired.push(fixture)
  fs.mkdirSync(path.join(directory, 'tools/events'), { recursive: true })
  fs.mkdirSync(path.join(directory, 'backend/src/main/java'), { recursive: true })
  fs.writeFileSync(path.join(directory, 'tools/events/retired-event-contracts.json'), JSON.stringify(manifest))
  for (const entry of manifest.retired) {
    const note = path.join(directory, entry.agentNote)
    fs.mkdirSync(path.dirname(note), { recursive: true })
    fs.writeFileSync(note, 'fixture')
  }
  return { directory, fixture, manifest }
}

test('当前文件树拒绝退役事件遗留的 Schema 和合法、非法样例，即使文件改名', context => {
  const { directory, fixture } = retirementRepository(context)
  assert.doesNotThrow(() => loadCurrentBundle(directory))
  for (const [file, value] of [
    ['schemas/renamed.schema.json', eventSchema({ eventType: fixture.eventType })],
    ['examples/renamed.json', validExample(fixture.eventType)],
    ['examples/renamed.invalid.json', { eventType: fixture.eventType, eventVersion: 1 }],
  ]) {
    const absolute = path.join(directory, 'contracts/events', file)
    fs.writeFileSync(absolute, JSON.stringify(value))
    assert.throws(() => loadCurrentBundle(directory), /仍有 Schema 或样例文件/u)
    fs.unlinkSync(absolute)
  }
})

test('退役决定归档后无需修改只追加清单，且必须保留同分类同名文件', context => {
  const { directory, fixture, manifest } = retirementRepository(context)
  const manifestFile = path.join(directory, 'tools/events/retired-event-contracts.json')
  const originalManifest = fs.readFileSync(manifestFile, 'utf8')
  const implemented = path.join(directory, fixture.agentNote)
  const archivedPath = fixture.agentNote.replace('/implemented/', '/archived/')
  const archived = path.join(directory, archivedPath)
  assert.doesNotThrow(() => loadCurrentBundle(directory))
  fs.unlinkSync(implemented)
  assert.throws(() => loadCurrentBundle(directory), /退役决策记录不存在/u)
  for (const wrongPath of [archivedPath.replace('/product/', '/process/'), archivedPath.replace('.md', '-other.md')]) {
    const file = path.join(directory, wrongPath)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, 'fixture')
    assert.throws(() => loadCurrentBundle(directory), /退役决策记录不存在/u)
  }
  fs.mkdirSync(path.dirname(archived), { recursive: true })
  fs.writeFileSync(archived, 'fixture')
  assert.doesNotThrow(() => loadCurrentBundle(directory))
  assert.equal(fs.readFileSync(manifestFile, 'utf8'), originalManifest)
  manifest.retired.at(-1).agentNote = archivedPath
  fs.writeFileSync(manifestFile, JSON.stringify(manifest))
  assert.doesNotThrow(() => loadCurrentBundle(directory))
  fs.unlinkSync(archived)
  assert.throws(() => loadCurrentBundle(directory), /退役决策记录不存在/u)
})

test('退役事件不能以生产者、订阅或共享常量重新进入任意后端 Java 包', context => {
  const { directory, fixture } = retirementRepository(context)
  for (const [relative, source] of [
    ['catalog/application/Producer.java', `eventPort.append(new EventDraft(\n  "${fixture.eventType}", 1, "Product"));`],
    ['audit/api/Subscriber.java', `private static final Set<String> EVENTS = Set.of(\n  "${fixture.eventType}");`],
    ['future/infrastructure/EventTypes.java', `static final String TYPE = "${fixture.eventType}";`],
  ]) {
    const file = path.join(directory, 'backend/src/main/java/com/yumpoo/platform', relative)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, source)
    assert.throws(() => loadCurrentBundle(directory), error => {
      assert.match(error.message, /退役事件类型仍出现在后端源码/u)
      assert(error.message.includes(fixture.eventType))
      assert(error.message.includes(`${relative}:${source.includes('\n') ? 2 : 1}`))
      return true
    })
    fs.unlinkSync(file)
  }
})

test('project_created v1 退役且 v2 保留时允许源码字面量，全部版本退役时仍拒绝', context => {
  const { directory } = retirementRepository(context)
  const eventsRoot = path.join(directory, 'contracts/events')
  const catalogFile = path.join(eventsRoot, 'catalog.yaml')
  const catalog = parseYaml(fs.readFileSync(catalogFile, 'utf8'))
  const previous = catalog.events.find(event => event.eventType === 'catalog.project_created')
  const next = {
    ...previous, eventVersion: 2,
    schema: previous.schema.replace('-v1', '-v2'),
    validExamples: previous.validExamples.map(file => file.replace('-v1', '-v2')),
    invalidExamples: previous.invalidExamples.map(file => file.replace('-v1', '-v2')),
  }
  for (const relative of [previous.schema, ...previous.validExamples, ...previous.invalidExamples]) {
    const source = fs.readFileSync(path.join(eventsRoot, relative), 'utf8')
      .replaceAll('-v1', '-v2').replaceAll('"eventVersion": 1', '"eventVersion": 2')
      .replaceAll('"const": 1', '"const": 2')
    fs.writeFileSync(path.join(eventsRoot, relative.replace('-v1', '-v2')), source)
    fs.unlinkSync(path.join(eventsRoot, relative))
  }
  catalog.events = catalog.events.filter(event => event !== previous)
  catalog.events.push(next)
  fs.writeFileSync(catalogFile, stringifyYaml(catalog))
  const manifestFile = path.join(directory, 'tools/events/retired-event-contracts.json')
  const manifest = JSON.parse(fs.readFileSync(manifestFile, 'utf8'))
  manifest.retired.push({ ...retiredEntry, eventType: 'catalog.project_created' })
  fs.writeFileSync(manifestFile, JSON.stringify(manifest))
  fs.writeFileSync(path.join(directory, 'backend/src/main/java/Producer.java'),
    'new EventDraft("catalog.project_created", 2);')
  assert.doesNotThrow(() => loadCurrentBundle(directory))

  catalog.events = catalog.events.filter(event => event !== next)
  fs.writeFileSync(catalogFile, stringifyYaml(catalog))
  for (const relative of [next.schema, ...next.validExamples, ...next.invalidExamples]) {
    fs.unlinkSync(path.join(eventsRoot, relative))
  }
  manifest.retired.push({ ...retiredEntry, eventType: 'catalog.project_created', eventVersion: 2 })
  fs.writeFileSync(manifestFile, JSON.stringify(manifest))
  assert.throws(() => loadCurrentBundle(directory), /退役事件类型仍出现在后端源码：catalog.project_created/u)
})

test('退役源码扫描不误伤不同事件名及测试、历史迁移，但扫描目录缺失时失败', context => {
  const { directory, fixture } = retirementRepository(context)
  for (const [relative, source] of [
    ['backend/src/main/java/FutureProducer.java', `new EventDraft("${fixture.eventType}_later", 1);`],
    ['backend/src/test/java/HistoricalEventTest.java', `String fixture = "${fixture.eventType}";`],
    ['backend/src/main/resources/db/migration/V1__history.sql', `SELECT '${fixture.eventType}';`],
  ]) {
    const file = path.join(directory, relative)
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, source)
  }
  assert.doesNotThrow(() => loadCurrentBundle(directory))
  fs.unlinkSync(path.join(directory, 'backend/src/main/java/FutureProducer.java'))
  fs.rmdirSync(path.join(directory, 'backend/src/main/java'))
  assert.throws(() => loadCurrentBundle(directory), /缺少后端事件扫描目录/u)
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
