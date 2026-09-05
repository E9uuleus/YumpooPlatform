import {
  WorkItemRankPlacement,
  WorkItemViewType,
} from '../src/generated/models/index.js'
import { WorkItemPriority } from '../src/yumpoo-api-client.js'
import type {
  CreateWorkItemRequest,
  DeleteWorkItemRequest,
  ListProjectWorkItemsRequest,
  PatchWorkItemContentRequest,
  RankMoveWorkItemRequest,
  RestoreWorkItemRequest,
  TransitionWorkItemRequest,
  UpdateWorkItemRequest,
} from '../src/generated/apis/WorkItemsApi.js'

const projectId = '2a000000-0000-4000-8000-000000000400'
const contentId = '2a000000-0000-4000-8000-000000000401'
const workItemId = '2a000000-0000-4000-8000-000000000404'

const create: CreateWorkItemRequest = {
  projectId,
  xXSRFTOKEN: 'csrf-token',
  idempotencyKey: '2a000000-0000-4000-8000-000000000402',
  workItemCreateRequest: {
    contentId,
    title: '实现 M2-10',
    priority: WorkItemPriority.Medium,
    description: null,
    notes: null,
  },
}

const page: ListProjectWorkItemsRequest = {
  projectId,
  view: WorkItemViewType.Table,
  limit: 20,
  status: new Set(['BACKLOG', 'IN_PROGRESS']),
  contentId: new Set([contentId]),
  sort: ['CONTENT,ASC', 'UPDATED_AT,DESC'],
}

const update: UpdateWorkItemRequest = {
  workItemId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
  workItemUpdateRequest: {
    title: '实现 M2-11', priority: WorkItemPriority.High, assigneeUserId: null,
    description: null, notes: null, timelineStartDate: null, timelineEndDate: null, dueDate: null,
  },
}

const changeContent: PatchWorkItemContentRequest = {
  workItemId,
  xXSRFTOKEN: 'csrf-token',
  ifMatch: '"0"',
  idempotencyKey: '2a000000-0000-4000-8000-000000000410',
  workItemContentPatchRequest: { contentId },
}

const transition: TransitionWorkItemRequest = {
  workItemId, xXSRFTOKEN: 'csrf-token', ifMatch: '"0"',
  idempotencyKey: '2a000000-0000-4000-8000-000000000405',
  workItemTransitionRequest: { toStatus: 'READY', resolution: '需求已澄清' },
}

const rankMove: RankMoveWorkItemRequest = {
  workItemId, xXSRFTOKEN: 'csrf-token', ifMatch: '"0"',
  idempotencyKey: '2a000000-0000-4000-8000-000000000406',
  workItemRankMoveRequest: {
    toStatus: 'READY', placement: WorkItemRankPlacement.Before,
    anchorWorkItemId: '2a000000-0000-4000-8000-000000000407', resolution: null,
  },
}

const softDelete: DeleteWorkItemRequest = {
  workItemId, xXSRFTOKEN: 'csrf-token', ifMatch: '"0"',
  idempotencyKey: '2a000000-0000-4000-8000-000000000408',
  workItemDeleteRequest: { reason: '重复工作项' },
}

const restore: RestoreWorkItemRequest = {
  workItemId, xXSRFTOKEN: 'csrf-token', ifMatch: '"1"',
  idempotencyKey: '2a000000-0000-4000-8000-000000000409',
}

// @ts-expect-error 创建工作项必须提交类别。
const missingContent: CreateWorkItemRequest['workItemCreateRequest'] = {
  title: '缺少类别', priority: null, description: null, notes: null,
}
// @ts-expect-error 类别切换必须提交目标类别。
const missingTargetContent: PatchWorkItemContentRequest['workItemContentPatchRequest'] = {}

void create
void page
void update
void changeContent
void transition
void rankMove
void softDelete
void restore
void missingContent
void missingTargetContent
