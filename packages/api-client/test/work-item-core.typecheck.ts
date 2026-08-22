import { WorkItemPriority } from '../src/generated/models/index.js'
import type {
  CreateWorkItemRequest,
  ListContentWorkItemsRequest,
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
  page: 0,
  size: 20,
  status: new Set(['BACKLOG', 'IN_PROGRESS']),
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
void update
void transition
void missingTarget
void missingPriorityBody
void missingDueDateBody
