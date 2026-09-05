import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8')
const exists = relative => fs.existsSync(path.join(root, relative))

export function verifyContentCategoryRefactorAssets() {
  const migration = read('backend/src/main/resources/db/migration/workitem/V48__refactor_content_as_work_item_category.sql')
  const content = read('backend/src/main/java/com/yumpoo/platform/workitem/domain/Content.java')
  const contentService = read('backend/src/main/java/com/yumpoo/platform/workitem/application/ContentService.java')
  const contentController = read('backend/src/main/java/com/yumpoo/platform/workitem/api/ContentController.java')
  const workItemService = read('backend/src/main/java/com/yumpoo/platform/workitem/application/WorkItemService.java')
  const workItemRepository = read('backend/src/main/java/com/yumpoo/platform/workitem/infrastructure/JdbcWorkItemRepository.java')
  const workItemController = read('backend/src/main/java/com/yumpoo/platform/workitem/api/WorkItemController.java')
  const openapi = read('contracts/openapi/yumpoo-v1.yaml')
  const eventCatalog = read('contracts/events/catalog.yaml')
  const overview = read('frontend/web-app/src/views/projects/ProjectOverviewView.vue')
  const subitems = read('frontend/web-app/src/components/projects/ProjectWorkItemSubitemsTable.vue')
  const editor = read('frontend/web-app/src/components/projects/WorkItemContentPopoverContent.vue')
  const routes = read('frontend/web-app/src/router/index.ts')

  for (const fragment of [
    'DROP COLUMN work_item_type', 'DROP COLUMN default_view_type', 'DROP COLUMN view_config',
    'ALTER TABLE yumpoo.work_item_update DROP COLUMN content_id', 'ALTER TABLE yumpoo.work_item',
    'DROP COLUMN type', 'PRIMARY KEY (project_id, status_code)', 'UPDATE yumpoo.work_item SET rank = project_sort_key',
    'BRIGHT_BLUE', 'BRIGHT_GREEN', 'DARK_RED', 'content_catalog_version',
  ]) assert(migration.includes(fragment), `V48 缺少 ${fragment}`)

  for (const fragment of ['protectedContent', 'everUsed', 'sortOrder', 'colorToken', 'deletedAt']) {
    assert(content.includes(fragment), `Content 目录模型缺少 ${fragment}`)
  }
  for (const fragment of ['LAST_ACTIVE_CONTENT', 'PROTECTED_CONTENT', 'CONTENT_IN_USE',
    'lockCatalogVersion', 'workitem.content_created', 'workitem.content_updated', 'workitem.content_deleted']) {
    assert(contentService.includes(fragment), `Content 服务缺少 ${fragment}`)
  }
  for (const fragment of ['@GetMapping("/projects/{projectId}/contents")',
    '@PostMapping("/projects/{projectId}/contents")',
    '@PatchMapping("/projects/{projectId}/contents/{contentId}")',
    '@DeleteMapping("/projects/{projectId}/contents/{contentId}")']) {
    assert(contentController.includes(fragment), `Content API 缺少 ${fragment}`)
  }
  assert(!contentController.includes('/archive') && !contentController.includes('/restore'), 'Content API 仍公开旧归档动作')

  for (const fragment of ['case "CONTENT"', 'case CONTENT', 'contentNames', 'changeContent(',
    'contentName', 'contentColorToken', 'workitem.work_item_fields_changed']) {
    assert(workItemService.includes(fragment) || workItemRepository.includes(fragment), `Work Item 类别行为缺少 ${fragment}`)
  }
  assert(workItemRepository.includes('view == WorkItemViewType.KANBAN'), '项目级看板查询未使用独立视图类型')
  assert(workItemController.includes('@PatchMapping("/work-items/{workItemId}/content")'), '缺少工作项类别切换 API')

  for (const fragment of ['/projects/{projectId}/contents:', '/projects/{projectId}/contents/{contentId}:',
    '/projects/{projectId}/work-items:', '/work-items/{workItemId}/content:', 'CONTENT',
    'contentName:', 'contentColorToken:', 'WorkItemViewType:']) {
    assert(openapi.includes(fragment), `OpenAPI 缺少 ${fragment}`)
  }
  for (const stale of ['/contents/{contentId}/work-items:', '/contents/{contentId}/archive:',
    '/contents/{contentId}/restore:', 'ContentViewConfig:', 'WorkItemType:']) {
    assert(!openapi.includes(stale), `OpenAPI 仍含旧契约 ${stale}`)
  }
  for (const event of ['workitem.content_created', 'workitem.content_updated', 'workitem.content_deleted',
    'workitem.work_item_created', 'workitem.work_item_fields_changed', 'eventVersion: 2']) {
    assert(eventCatalog.includes(event), `事件目录缺少 ${event}`)
  }

  for (const fragment of ['WorkItemContentPopoverContent', 'patchWorkItemContent',
    '--work-item-table-row-height: 36px', 'height: 26px', 'margin: 5px 24px']) {
    assert(overview.includes(fragment), `项目工作项总表缺少 ${fragment}`)
  }
  for (const fragment of ['WorkItemContentPopoverContent', '--subitem-table-row-height: 36px',
    'height: 26px', 'margin: 5px 24px']) assert(subitems.includes(fragment), `子项表缺少 ${fragment}`)
  for (const fragment of ['canManage', 'protectedContent', 'inUse', 'draggable="true"',
    'border-radius: var(--yp-radius-xs)', 'height: 34px']) assert(editor.includes(fragment), `类别选择/管理弹窗缺少 ${fragment}`)

  assert(!routes.includes('projects/:projectId/contents'), '路由仍公开 Content 配置页')
  for (const removed of [
    'frontend/web-app/src/views/projects/ProjectContentsView.vue',
    'frontend/web-app/src/views/projects/ContentWorkItemsView.vue',
    'frontend/web-app/src/components/projects/ContentTableQueryEditor.vue',
    'backend/src/main/java/com/yumpoo/platform/workitem/application/ContentViewConfig.java',
    'backend/src/main/java/com/yumpoo/platform/workitem/application/ContentViewConfigCodec.java',
  ]) assert(!exists(removed), `旧资产仍存在：${removed}`)
}

export function verifyHistoricalMilestone(milestone) {
  const normalized = milestone.toLowerCase()
  const report = JSON.parse(read(`evidence/${normalized}/verification-report.json`))
  const acceptance = JSON.parse(read(`evidence/${normalized}/acceptance-matrix.json`))
  assert(report.milestone === milestone && report.status === 'PASS', `${milestone} 验证报告无效`)
  assert(Array.isArray(acceptance.verifiedSlices) && acceptance.verifiedSlices.length > 0,
    `${milestone} 验收矩阵缺少已验证切片`)
}

function assert(condition, message) {
  if (!condition) throw new Error(`Content 类别重构资产验证失败：${message}`)
}
