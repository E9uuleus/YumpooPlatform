import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { assert, loadCurrentBundle, readFreezeManifest } from './event-contract-compat.mjs'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const manifest = readFreezeManifest(repositoryRoot)
const applicationRoot = path.join(repositoryRoot, 'backend', 'src', 'main', 'java',
  'com', 'yumpoo', 'platform', 'workitem', 'application')
const serviceFiles = new Map([
  ['WorkItemService', path.join(applicationRoot, 'WorkItemService.java')],
  ['WorkItemUpdateService', path.join(applicationRoot, 'WorkItemUpdateService.java')],
  ['WorkItemRelationService', path.join(applicationRoot, 'WorkItemRelationService.java')],
])
const eventPattern = /workitem\.work_item_[a-z0-9_]+/gu

for (const [producer, file] of serviceFiles) {
  const actual = matches(read(file), eventPattern)
  const expected = new Set(manifest.events
    .filter((event) => event.producers.includes(producer))
    .map((event) => event.eventType))
  assertSameSet(`${producer} 生产事件`, actual, expected)
}

const activityFile = path.join(repositoryRoot, 'backend', 'src', 'main', 'java',
  'com', 'yumpoo', 'platform', 'audit', 'api', 'ActivityProjectionService.java')
const activitySource = read(activityFile)
const workItemBlock = activitySource.match(
  /private static final Set<String> WORK_ITEM_EVENTS = Set\.of\(([\s\S]*?)\);/u)?.[1]
assert(workItemBlock, 'ActivityProjectionService 缺少 WORK_ITEM_EVENTS 清单')
assertSameSet('Activity v1 订阅', matches(workItemBlock, eventPattern),
  new Set(manifest.events.map((event) => event.eventType)))

const frozenBundle = loadCurrentBundle(repositoryRoot, manifest)
assertSameSet('冻结 v1 Schema 与合法样例',
  new Set(frozenBundle.events.map((event) => event.eventType)),
  new Set(manifest.events.map((event) => event.eventType)))

console.log('M2-23 已对账 14 个冻结事件的生产者、Activity 订阅、Schema 与合法样例。')

function read(file) {
  assert(fs.statSync(file, { throwIfNoEntry: false })?.isFile(), `缺少审计文件 ${file}`)
  return fs.readFileSync(file, 'utf8')
}

function matches(source, pattern, capture = 0) {
  return new Set([...source.matchAll(pattern)].map((match) => match[capture]))
}

function assertSameSet(label, actual, expected) {
  const missing = [...expected].filter((value) => !actual.has(value)).sort()
  const unexpected = [...actual].filter((value) => !expected.has(value)).sort()
  assert(missing.length === 0 && unexpected.length === 0,
    `${label} 与冻结清单不一致；缺少=[${missing.join(', ')}]，新增=[${unexpected.join(', ')}]`)
}
