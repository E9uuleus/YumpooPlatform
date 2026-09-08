import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parse } from 'yaml'

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')

export function verifyContentCategoryRefactorAssets(root = repositoryRoot) {
  const spec = parse(fs.readFileSync(path.join(root, 'contracts/openapi/yumpoo-v1.yaml'), 'utf8'))
  const catalog = parse(fs.readFileSync(path.join(root, 'contracts/events/catalog.yaml'), 'utf8'))
  const operations = [
    ['/projects/{projectId}/contents', 'get', 'listProjectContents', []],
    ['/projects/{projectId}/contents', 'post', 'createContent', ['XsrfToken', 'IdempotencyKey']],
    ['/projects/{projectId}/contents/{contentId}', 'patch', 'updateContent', ['XsrfToken', 'IfMatch']],
    ['/projects/{projectId}/contents/{contentId}', 'delete', 'deleteContent', ['XsrfToken', 'IfMatch']],
    ['/work-items/{workItemId}/content', 'patch', 'patchWorkItemContent', ['XsrfToken', 'IfMatch', 'IdempotencyKey']],
  ]
  for (const [route, method, operationId, headers] of operations) {
    const operation = spec.paths?.[route]?.[method]
    assert(operation?.operationId === operationId, `缺少 ${method.toUpperCase()} ${route}`)
    assert(operation.security?.some(item => Object.hasOwn(item, 'sessionCookie')), `${operationId} 缺少会话认证`)
    for (const header of headers) {
      assert(operation.parameters?.some(item => item.$ref === `#/components/parameters/${header}`), `${operationId} 缺少 ${header}`)
    }
  }
  for (const route of ['/contents/{contentId}/work-items', '/contents/{contentId}/archive', '/contents/{contentId}/restore']) {
    assert(!spec.paths?.[route], `仍公开旧 Content 契约 ${route}`)
  }
  const schemas = spec.components.schemas
  for (const field of ['projectId', 'colorToken', 'sortOrder', 'active', 'protectedContent', 'inUse', 'rowVersion']) {
    assert(schemas.Content.required.includes(field) && schemas.Content.properties[field], `Content 缺少必需字段 ${field}`)
  }
  for (const field of ['protectedContent', 'inUse', 'rowVersion']) {
    assert(schemas.Content.properties[field].readOnly === true, `${field} 必须由服务端管理`)
  }
  assert(!schemas.ContentViewConfig && !schemas.WorkItemType, '仍公开旧 Content 配置或工作项类型')
  for (const [eventType, eventVersion] of [
    ['workitem.content_created', 2], ['workitem.content_updated', 2], ['workitem.content_deleted', 2],
    ['workitem.work_item_created', 2], ['workitem.work_item_fields_changed', 2],
  ]) {
    assert(catalog.events.some(item => item.eventType === eventType && item.eventVersion === eventVersion),
      `事件目录缺少 ${eventType}@${eventVersion}`)
  }
}

export function verifyHistoricalMilestone(milestone) {
  const normalized = milestone.toLowerCase()
  const read = name => JSON.parse(fs.readFileSync(path.join(repositoryRoot, `evidence/${normalized}/${name}.json`), 'utf8'))
  const report = read('verification-report')
  const acceptance = read('acceptance-matrix')
  assert(report.milestone === milestone && report.status === 'PASS', `${milestone} 历史报告无效`)
  assert(Array.isArray(acceptance.verifiedSlices) && acceptance.verifiedSlices.length > 0,
    `${milestone} 历史验收矩阵缺少已验证切片`)
}

function assert(condition, message) {
  if (!condition) throw new Error(`Content 类别契约验证失败：${message}`)
}
