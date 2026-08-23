import {
  ContentViewType,
  ContentSortDirection,
  ContentSortField,
  WorkItemPriority,
  WorkItemRankPlacement,
} from '../src/generated/models/index.js'
import type {
  CreateWorkItemRequest,
  ListContentWorkItemsRequest,
  RankMoveWorkItemRequest,
  TransitionWorkItemRequest,
  UpdateWorkItemRequest,
} from '../src/generated/apis/WorkItemsApi.js'

const create: CreateWorkItemRequest = {
  contentId: '2a000000-0000-4000-8000-000000000401',
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '2a000000-0000-4000-8000-000000000402',
  workItemCreateRequest: {
    title: '实现 M2-10',
    priority: WorkItemPriority.Medium,
    description: null,
    notes: null,
  },
}

const groupedPage: ListContentWorkItemsRequest = {
  contentId: create.contentId,
  view: ContentViewType.Table,
  page: 0,
  size: 20,
  status: new Set(['BACKLOG', 'IN_PROGRESS']),
}

const kanbanPage: ListContentWorkItemsRequest = {
  contentId: create.contentId,
  view: ContentViewType.Kanban,
  page: 0,
  size: 20,
  status: new Set(['BACKLOG']),
}

const advancedPage: ListContentWorkItemsRequest = {
  contentId: create.contentId,
  page: 1,
  size: 20,
  q: '稳定分页',
  status: new Set(['BACKLOG']),
  priority: new Set([WorkItemPriority.High, WorkItemPriority.Urgent]),
  assigneeUserId: new Set(['2a000000-0000-4000-8000-000000000403']),
  dueFrom: new Date('2026-08-01T00:00:00.000Z'),
  dueTo: new Date('2026-08-31T00:00:00.000Z'),
  updatedAfter: new Date('2026-08-01T00:00:00.000Z'),
  sort: [
    `${ContentSortField.Priority},${ContentSortDirection.Desc}`,
    `${ContentSortField.UpdatedAt},${ContentSortDirection.Desc}`,
  ],
}

create.workItemCreateRequest.assigneeUserId = '2a000000-0000-4000-8000-000000000403'

const update: UpdateWorkItemRequest = {
  workItemId: '2a000000-0000-4000-8000-000000000404',
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
  workItemUpdateRequest: {
    title: '实现 M2-11',
    priority: WorkItemPriority.High,
    assigneeUserId: null,
    description: null,
    notes: null,
    timelineStartDate: null,
    timelineEndDate: null,
    dueDate: null,
  },
}

const transition: TransitionWorkItemRequest = {
  workItemId: update.workItemId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
  idempotencyKey: '2a000000-0000-4000-8000-000000000405',
  workItemTransitionRequest: {
    toStatus: 'READY',
    resolution: '需求已澄清',
  },
}

const rankMove: RankMoveWorkItemRequest = {
  workItemId: update.workItemId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
  idempotencyKey: '2a000000-0000-4000-8000-000000000406',
  workItemRankMoveRequest: {
    toStatus: 'READY',
    placement: WorkItemRankPlacement.Before,
    anchorWorkItemId: '2a000000-0000-4000-8000-000000000407',
    resolution: null,
  },
}

// @ts-expect-error M2-14 rank move 必须明确目标状态与定位方式。
const missingPlacement: RankMoveWorkItemRequest['workItemRankMoveRequest'] = {
  toStatus: 'READY',
}

// @ts-expect-error M2-12 状态迁移必须明确提交目标状态。
const missingTarget: TransitionWorkItemRequest['workItemTransitionRequest'] = {
  resolution: null,
}

// @ts-expect-error API 要求客户端明确提交优先级。
const missingPriorityBody: CreateWorkItemRequest['workItemCreateRequest'] = {
  title: '缺少优先级', description: null, notes: null,
}

// @ts-expect-error M2-11 PATCH 要求客户端提交完整字段快照。
const missingDueDateBody: UpdateWorkItemRequest['workItemUpdateRequest'] = {
  title: '缺少截止日', priority: WorkItemPriority.Medium, assigneeUserId: null,
  description: null, notes: null, timelineStartDate: null, timelineEndDate: null,
}

void create
void groupedPage
void kanbanPage
void advancedPage
void update
void transition
void rankMove
void missingPlacement
void missingTarget
void missingPriorityBody
void missingDueDateBody
