import {
  WorkItemLabelColorToken,
  ProjectActorAccess,
  ProjectLifecycle,
  WorkItemStatusCategory,
  type ProjectContentCatalog,
  type ProjectDetail,
  type WorkItemDetail,
  type ProjectWorkItemCursorPage,
  type ProjectWorkItemListItem,
} from '@yumpoo/api-client'
import { mount } from '@vue/test-utils'
import { defineComponent, reactive } from 'vue'
import { vi } from 'vitest'
import type { GroupField } from './workItemGrouping'
import type { WorkItemTableSource } from './workItemTableSource'
import ProjectWorkItems from './ProjectWorkItems.vue'
import { state } from './projectWorkItemsTestMocks'

export function project(id: string): ProjectDetail {
  return {
    id,
    workspaceId: 'workspace-1', workspaceCode: 'MAIN', workspaceName: '主工作空间',
    code: id.toUpperCase(), name: `项目 ${id}`, description: null,
     lifecycle: ProjectLifecycle.Active,
    ownerUserId: 'owner-1', ownerDisplayName: '负责人',

     actorAccess: ProjectActorAccess.Owner,
    capabilities: {
      canUpdateSettings: true,  canManageMembers: true,
      canReassignOwner: true, canArchive: true,
      canRestore: false, canMoveWorkspace: false, canOverrideArchive: false,
    },
    rowVersion: 1, etag: '"1"', createdAt: new Date('2026-08-25T00:00:00Z'),
    updatedAt: new Date('2026-08-25T00:00:00Z'),  archivedAt: null,
  }
}

export function catalog(): ProjectContentCatalog {
  return {
    items: [{
      id: 'content-1', projectId: 'project-1', code: 'REQ', name: '产品需求',
      colorToken: WorkItemLabelColorToken.BrightBlue, sortOrder: 10, active: true,
      protectedContent: true, inUse: true,
      rowVersion: 1, createdAt: new Date(), createdByUserId: 'owner-1',
      updatedAt: new Date(), updatedByUserId: 'owner-1',
    }],
    rowVersion: 1, etag: '"1"', canManage: true,
  }
}

export function item(id = 'item-1'): ProjectWorkItemListItem {
  return {
    id, projectId: 'project-1', contentId: 'content-1', contentName: '产品需求', contentColorToken: WorkItemLabelColorToken.BrightBlue, itemNo: 'WI-1',
    title: '实现项目工作项首页', statusCode: 'BACKLOG', statusCategory: WorkItemStatusCategory.Todo,
    priority: null, assigneeUserId: null, assigneeDisplayName: null,
    dueDate: null, rowVersion: 1, etag: '"1"',
    subitemCount: 0,
    capabilities: { canEditFields: true, canMoveInKanban: true, canMoveInProjectOrder: true,
      canDiscuss: true, canDelete: true, canRestore: false, availableTransitions: [] },
    updatedAt: new Date('2026-08-25T01:00:00Z'),
  }
}

export function page(items = [item()]): ProjectWorkItemCursorPage {
  return { items, nextCursor: null }
}

export function mountView(discussion?: { hasDraft: boolean, busy: boolean, discardDraft: () => void }, props: { embeddedProjectId?: string; preferenceScope?: string; source?: WorkItemTableSource; myWork?: boolean; groupField?: GroupField } = {}) {
  return mount(ProjectWorkItems, {
    props,
    global: {
      stubs: {
        InlineProblem: true,
        WorkItemDetailPanel: discussion ? defineComponent({
          name: 'WorkItemDetailPanel',
          props: { detail: Object },
          setup(_props, { expose }) { expose(discussion); return {} },
          template: '<div />',
        }) : true,
        ProjectWorkspaceHeader: {
          props: ['project'],
          template: '<div data-testid="project-name">{{ project.name }}</div>',
        },
      },
    },
  })
}

export function installWorkItemsTestDefaults() {
  localStorage.clear()
  state.route = reactive({ params: { projectId: 'project-1' }, query: {} })
  Object.values(state).filter(value => typeof value === 'function').forEach(mock => mock.mockReset())
  state.push.mockImplementation(async ({ query }: { query: Record<string, string> }) => {
    state.route.query = query
  })
  state.replace.mockImplementation(async ({ query }: { query: Record<string, string> }) => {
    state.route.query = query
  })
  state.getProject.mockImplementation(({ projectId }: { projectId: string }) => Promise.resolve(project(projectId)))
  state.listProjectContents.mockResolvedValue(catalog())
  state.listConnectColumns.mockResolvedValue({ items: [], incomingAvailable: false, incomingColumns: [], canManage: true, canDelete: true })
  state.listWorkItemConnectionCells.mockResolvedValue({ items: [] })
  state.searchConnectTargetProjects.mockResolvedValue({ items: [], page: 0, size: 20, totalPages: 0, totalElements: 0 })
  state.getProjectWorkItemLabels.mockResolvedValue({
    statuses: [
      { code: 'BACKLOG', displayName: '待开始', colorToken: 'BLUE', statusCategory: 'TODO',
        sortOrder: 10, active: true, protectedLabel: false, inUse: true },
      { code: 'DONE', displayName: '已完成', colorToken: 'GREEN', statusCategory: 'DONE',
        sortOrder: 20, active: true, protectedLabel: false, inUse: false },
    ], priorities: [], rowVersion: 0, etag: '"0"', canManage: true,
  } as never)
  state.listProjectMembers.mockResolvedValue({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
  state.listProjectWorkItems.mockResolvedValue(page())
  state.listWorkItemSubitems.mockResolvedValue({ items: [] })
  state.listWorkItemRelations.mockResolvedValue({ items: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
  state.listProjectWorkItemFilterOptions.mockResolvedValue({ items: [], nextCursor: null })
  state.createWorkItem.mockResolvedValue({ ...item('created'), itemNo: 'WI-2' } as unknown as WorkItemDetail)
  state.createWorkItemSubitem.mockResolvedValue({ ...item('subitem-created'), itemNo: 'WI-3' } as unknown as WorkItemDetail)
  state.getWorkItem.mockResolvedValue(item() as unknown as WorkItemDetail)
  state.moveProjectWorkItemOrder.mockResolvedValue({ ...item('created'), itemNo: 'WI-2' } as unknown as WorkItemDetail)
}

export function restoreWorkItemsTestGlobals() { vi.restoreAllMocks(); vi.unstubAllGlobals() }
